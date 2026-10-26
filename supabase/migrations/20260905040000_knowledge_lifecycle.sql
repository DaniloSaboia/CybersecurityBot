-- ============================================================================
-- Sentinel KMS — governed, concurrent knowledge lifecycle
-- ============================================================================
-- Revisions and evidence snapshots deliberately remain immutable.  Workflow
-- state is kept in a separate projection with an optimistic version and every
-- durable state change is represented by an append-only lifecycle event.
--
-- Application mutations must lock the article governance row, compare the
-- supplied state versions, update the workflow state, append an event/review,
-- and (only for publication) move article_governance.current_revision_id in
-- one transaction.  Direct SQL cannot make a non-approved workflow revision
-- the published pointer.

-- A version is the compare-and-swap token for article-level operations that
-- change the published pointer, expiry, review schedule, or policy projection.
ALTER TABLE article_governance
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_article_governance_state_version_nonnegative'
          AND conrelid = 'public.article_governance'::regclass
    ) THEN
        ALTER TABLE article_governance
            ADD CONSTRAINT ck_article_governance_state_version_nonnegative
            CHECK (state_version >= 0);
    END IF;
END $$;

-- This is intentionally a mutable projection rather than a mutable revision.
-- The composite key binds the state to the immutable article/workspace/
-- classification facts of its revision.
CREATE TABLE IF NOT EXISTS article_revision_workflow_states (
    article_revision_id TEXT PRIMARY KEY,
    article_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    classification TEXT NOT NULL CHECK (classification IN
        ('public', 'internal', 'confidential', 'restricted')),
    approval_status TEXT NOT NULL CHECK (approval_status IN
        ('draft', 'pending_review', 'approved', 'rejected', 'superseded', 'rolled_back')),
    lifecycle_status TEXT NOT NULL CHECK (lifecycle_status IN
        ('active', 'superseded', 'expired', 'archived')),
    review_due_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    approved_by_sub TEXT,
    approved_at TIMESTAMPTZ,
    state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_article_revision_workflow_dates
        CHECK (review_due_at IS NULL OR expires_at IS NULL OR review_due_at <= expires_at),
    CONSTRAINT ck_article_revision_workflow_approval_identity
        CHECK (approval_status <> 'approved' OR (approved_by_sub IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT uq_article_revision_workflow_binding
        UNIQUE (article_revision_id, article_id, workspace_id, classification),
    CONSTRAINT fk_article_revision_workflow_revision_binding
        FOREIGN KEY (article_revision_id, article_id, workspace_id, classification)
        REFERENCES article_revisions (id, article_id, workspace_id, classification)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_article_revision_workflow_article_state
    ON article_revision_workflow_states (article_id, approval_status, lifecycle_status);
CREATE INDEX IF NOT EXISTS idx_article_revision_workflow_review_due
    ON article_revision_workflow_states (review_due_at)
    WHERE review_due_at IS NOT NULL;

DROP TRIGGER IF EXISTS set_article_revision_workflow_states_updated_at
    ON article_revision_workflow_states;
CREATE TRIGGER set_article_revision_workflow_states_updated_at
    BEFORE UPDATE ON article_revision_workflow_states
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- The lifecycle ledger has enough transition detail to reconstruct why a
-- revision became visible, hidden, superseded, expired, or rolled back.  It is
-- append-only; source/evidence content is never copied into event metadata.
CREATE TABLE IF NOT EXISTS article_revision_lifecycle_events (
    id TEXT PRIMARY KEY,
    article_revision_id TEXT NOT NULL,
    article_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    classification TEXT NOT NULL CHECK (classification IN
        ('public', 'internal', 'confidential', 'restricted')),
    event_type TEXT NOT NULL CHECK (event_type IN
        ('migration_baseline', 'draft_created', 'submitted', 'changes_requested',
         'approved', 'rejected', 'superseded', 'expired', 'archived',
         'review_due_changed', 'expiry_changed', 'rollback_created', 'rolled_back')),
    from_approval_status TEXT CHECK (from_approval_status IS NULL OR from_approval_status IN
        ('draft', 'pending_review', 'approved', 'rejected', 'superseded', 'rolled_back')),
    to_approval_status TEXT NOT NULL CHECK (to_approval_status IN
        ('draft', 'pending_review', 'approved', 'rejected', 'superseded', 'rolled_back')),
    from_lifecycle_status TEXT CHECK (from_lifecycle_status IS NULL OR from_lifecycle_status IN
        ('active', 'superseded', 'expired', 'archived')),
    to_lifecycle_status TEXT NOT NULL CHECK (to_lifecycle_status IN
        ('active', 'superseded', 'expired', 'archived')),
    expected_state_version BIGINT NOT NULL CHECK (expected_state_version >= -1),
    resulting_state_version BIGINT NOT NULL CHECK (resulting_state_version >= 0),
    -- Article-level CAS version consumed by this event. It authorizes a
    -- governance version advance even when the current published pointer does
    -- not move (for example a draft/submission/rejection).
    governance_state_version BIGINT NOT NULL DEFAULT 0 CHECK (governance_state_version >= 0),
    actor_sub TEXT NOT NULL,
    comment TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(metadata) = 'object'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_article_revision_lifecycle_event_version
        UNIQUE (article_revision_id, resulting_state_version),
    CONSTRAINT fk_article_revision_lifecycle_event_revision_binding
        FOREIGN KEY (article_revision_id, article_id, workspace_id, classification)
        REFERENCES article_revisions (id, article_id, workspace_id, classification)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_article_revision_lifecycle_events_article_time
    ON article_revision_lifecycle_events (article_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_article_revision_lifecycle_events_revision_time
    ON article_revision_lifecycle_events (article_revision_id, occurred_at DESC);

-- Publication creates this durable work item in the same database transaction
-- as the pointer move.  A worker must fence on article_id + revision_id +
-- governance_state_version before replacing source_key article:<article_id>.
-- This means a late reindex for a superseded revision cannot restore stale text.
CREATE TABLE IF NOT EXISTS article_revision_reindex_outbox (
    id TEXT PRIMARY KEY,
    article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE RESTRICT,
    article_revision_id TEXT NOT NULL,
    workspace_id TEXT NOT NULL,
    classification TEXT NOT NULL CHECK (classification IN
        ('public', 'internal', 'confidential', 'restricted')),
    source_key TEXT NOT NULL CHECK (source_key = 'article:' || article_id),
    governance_state_version BIGINT NOT NULL CHECK (governance_state_version >= 0),
    operation TEXT NOT NULL DEFAULT 'replace' CHECK (operation IN ('replace')),
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN
        ('pending', 'claimed', 'completed', 'cancelled', 'failed')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    claim_token TEXT,
    claim_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_article_revision_reindex_outbox_fence
        UNIQUE (article_id, governance_state_version),
    CONSTRAINT fk_article_revision_reindex_outbox_revision_binding
        FOREIGN KEY (article_revision_id, article_id, workspace_id, classification)
        REFERENCES article_revisions (id, article_id, workspace_id, classification)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_article_revision_reindex_outbox_claimable
    ON article_revision_reindex_outbox (created_at, claim_until)
    WHERE state IN ('pending', 'claimed');

-- Lineage facts are immutable too.  The existing composite revision key makes
-- a cross-article/workspace/classification supersession or rollback impossible.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_article_revisions_supersedes_binding'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT fk_article_revisions_supersedes_binding
            FOREIGN KEY (supersedes_revision_id, article_id, workspace_id, classification)
            REFERENCES article_revisions (id, article_id, workspace_id, classification)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_article_revisions_rollback_binding'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT fk_article_revisions_rollback_binding
            FOREIGN KEY (rollback_of_revision_id, article_id, workspace_id, classification)
            REFERENCES article_revisions (id, article_id, workspace_id, classification)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_article_revisions_no_self_supersession'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT ck_article_revisions_no_self_supersession
            CHECK (supersedes_revision_id IS NULL OR supersedes_revision_id <> id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_article_revisions_no_self_rollback'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT ck_article_revisions_no_self_rollback
            CHECK (rollback_of_revision_id IS NULL OR rollback_of_revision_id <> id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_article_revisions_distinct_lineage_targets'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT ck_article_revisions_distinct_lineage_targets
            CHECK (supersedes_revision_id IS NULL OR rollback_of_revision_id IS NULL
                   OR supersedes_revision_id <> rollback_of_revision_id);
    END IF;
END $$;

CREATE OR REPLACE FUNCTION public.assert_article_revision_lineage()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    target_number INTEGER;
BEGIN
    IF NEW.supersedes_revision_id IS NOT NULL THEN
        SELECT revision_number INTO target_number
        FROM public.article_revisions
        WHERE id = NEW.supersedes_revision_id
          AND article_id = NEW.article_id
          AND workspace_id = NEW.workspace_id
          AND classification = NEW.classification;
        IF NOT FOUND OR target_number >= NEW.revision_number THEN
            RAISE EXCEPTION 'superseded revision must be an earlier revision in the same governed article';
        END IF;
    END IF;

    IF NEW.rollback_of_revision_id IS NOT NULL THEN
        SELECT revision_number INTO target_number
        FROM public.article_revisions
        WHERE id = NEW.rollback_of_revision_id
          AND article_id = NEW.article_id
          AND workspace_id = NEW.workspace_id
          AND classification = NEW.classification;
        IF NOT FOUND OR target_number >= NEW.revision_number THEN
            RAISE EXCEPTION 'rollback target must be an earlier revision in the same governed article';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS assert_article_revision_lineage ON article_revisions;
CREATE TRIGGER assert_article_revision_lineage
    BEFORE INSERT ON article_revisions
    FOR EACH ROW EXECUTE FUNCTION public.assert_article_revision_lineage();

-- A state transition cannot rewrite a revision's identity or skip optimistic
-- versions.  The legal status graph prevents terminal revisions from being
-- silently reactivated; a rollback is always a fresh revision instead.
CREATE OR REPLACE FUNCTION public.assert_article_revision_workflow_state()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.article_evidence_snapshots es
        WHERE es.article_revision_id = NEW.article_revision_id
    ) THEN
        RAISE EXCEPTION 'workflow state requires at least one immutable evidence snapshot';
    END IF;

    IF NEW.approval_status = 'approved'
       AND (NEW.approved_by_sub IS NULL OR btrim(NEW.approved_by_sub) = '' OR NEW.approved_at IS NULL) THEN
        RAISE EXCEPTION 'approved workflow state requires an approver and approval time';
    END IF;

    IF NEW.approval_status = 'superseded' AND NEW.lifecycle_status <> 'superseded' THEN
        RAISE EXCEPTION 'superseded workflow state must have superseded lifecycle status';
    END IF;
    IF NEW.approval_status = 'rolled_back' AND NEW.lifecycle_status NOT IN ('archived', 'superseded') THEN
        RAISE EXCEPTION 'rolled back workflow state must be archived or superseded';
    END IF;
    IF NEW.approval_status = 'rejected' AND NEW.lifecycle_status = 'active' THEN
        RAISE EXCEPTION 'rejected workflow state cannot remain active';
    END IF;
    IF NEW.approval_status = 'approved' AND NEW.lifecycle_status = 'active'
       AND NEW.expires_at IS NOT NULL AND NEW.expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'expired workflow state cannot be published as active';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.article_revision_id IS DISTINCT FROM OLD.article_revision_id
           OR NEW.article_id IS DISTINCT FROM OLD.article_id
           OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
           OR NEW.classification IS DISTINCT FROM OLD.classification
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'workflow state identity is immutable';
        END IF;
        IF NEW.state_version <> OLD.state_version + 1 THEN
            RAISE EXCEPTION 'workflow state update requires the next state version';
        END IF;

        IF (OLD.approval_status = 'draft' AND NEW.approval_status NOT IN ('draft', 'pending_review', 'rejected'))
           OR (OLD.approval_status = 'pending_review'
               AND NEW.approval_status NOT IN ('pending_review', 'draft', 'approved', 'rejected'))
           OR (OLD.approval_status = 'approved'
               AND NEW.approval_status NOT IN ('approved', 'superseded', 'rolled_back'))
           OR (OLD.approval_status IN ('rejected', 'superseded', 'rolled_back')
               AND NEW.approval_status <> OLD.approval_status) THEN
            RAISE EXCEPTION 'invalid immutable revision workflow transition: % -> %',
                OLD.approval_status, NEW.approval_status;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS assert_article_revision_workflow_state ON article_revision_workflow_states;
CREATE TRIGGER assert_article_revision_workflow_state
    BEFORE INSERT OR UPDATE ON article_revision_workflow_states
    FOR EACH ROW EXECUTE FUNCTION public.assert_article_revision_workflow_state();

-- State-event rows must describe the resulting, already-CASed workflow state.
-- This deliberately establishes the application write order: update/insert the
-- state, then append exactly one event with the resulting version.
CREATE OR REPLACE FUNCTION public.assert_article_revision_lifecycle_event()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.article_revision_workflow_states ws
        WHERE ws.article_revision_id = NEW.article_revision_id
          AND ws.article_id = NEW.article_id
          AND ws.workspace_id = NEW.workspace_id
          AND ws.classification = NEW.classification
          AND ws.state_version = NEW.resulting_state_version
          AND ws.approval_status = NEW.to_approval_status
          AND ws.lifecycle_status = NEW.to_lifecycle_status
    ) THEN
        RAISE EXCEPTION 'lifecycle event must match the resulting workflow state';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS assert_article_revision_lifecycle_event ON article_revision_lifecycle_events;
CREATE TRIGGER assert_article_revision_lifecycle_event
    BEFORE INSERT ON article_revision_lifecycle_events
    FOR EACH ROW EXECUTE FUNCTION public.assert_article_revision_lifecycle_event();

-- A published pointer is valid only when the exact bound workflow state is
-- approved, active, non-expired, evidence-backed, and mirrors the article
-- governance facts.  The state_version guard is the article-level CAS fence.
CREATE OR REPLACE FUNCTION public.assert_article_governance_workflow_binding()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    workflow public.article_revision_workflow_states%ROWTYPE;
BEGIN
    IF TG_OP = 'UPDATE'
	       AND (NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id
	            OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
	            OR NEW.classification IS DISTINCT FROM OLD.classification
	            OR NEW.read_policy IS DISTINCT FROM OLD.read_policy
	            OR NEW.approval_status IS DISTINCT FROM OLD.approval_status
            OR NEW.lifecycle_status IS DISTINCT FROM OLD.lifecycle_status
            OR NEW.review_due_at IS DISTINCT FROM OLD.review_due_at
            OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
            OR NEW.approved_by_sub IS DISTINCT FROM OLD.approved_by_sub
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
            OR NEW.state_version IS DISTINCT FROM OLD.state_version) THEN
        IF NEW.state_version <> OLD.state_version + 1 THEN
            RAISE EXCEPTION 'article governance mutation requires the next state version';
        END IF;
        -- Every policy or pointer change consumes one optimistic article
        -- version and must already have an append-only lifecycle record in
        -- this transaction.  Pointer moves are not exempt from the ledger.
        IF NOT EXISTS (
            SELECT 1
            FROM public.article_revision_lifecycle_events e
            WHERE e.article_id = NEW.article_id
              AND e.workspace_id = NEW.workspace_id
              AND e.classification = NEW.classification
              AND e.governance_state_version = NEW.state_version
        ) THEN
            RAISE EXCEPTION 'article governance state version requires a lifecycle event';
        END IF;
    END IF;

    -- A null pointer is the only safe state for a not-yet-published draft.
    -- It exposes no article because every read gate joins the pointer.
    IF NEW.current_revision_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT * INTO workflow
    FROM public.article_revision_workflow_states ws
    WHERE ws.article_revision_id = NEW.current_revision_id
      AND ws.article_id = NEW.article_id
      AND ws.workspace_id = NEW.workspace_id
      AND ws.classification = NEW.classification;

	IF NOT FOUND
	   OR NOT EXISTS (
           SELECT 1 FROM public.article_evidence_snapshots es
           WHERE es.article_revision_id = NEW.current_revision_id
       ) THEN
	    RAISE EXCEPTION 'current revision must resolve to an evidence-backed workflow state';
	END IF;

	-- An article-level event for some other revision cannot authorize a
	-- publication pointer change.  Bind the consumed governance version to the
	-- exact new pointer and to the resulting immutable workflow projection.
	-- This remains separate from the general version/event guard above because
	-- ordinary draft/review transitions legitimately consume an article version
	-- without moving the current pointer.
	IF TG_OP = 'UPDATE' AND NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id
	   AND NOT EXISTS (
	       SELECT 1
	       FROM public.article_revision_lifecycle_events e
	       WHERE e.article_revision_id = NEW.current_revision_id
	         AND e.article_id = NEW.article_id
	         AND e.workspace_id = NEW.workspace_id
	         AND e.classification = NEW.classification
	         AND e.governance_state_version = NEW.state_version
	         AND e.resulting_state_version = workflow.state_version
	         AND e.to_approval_status = workflow.approval_status
	         AND e.to_lifecycle_status = workflow.lifecycle_status
	   ) THEN
	    RAISE EXCEPTION 'current revision pointer requires an exact lifecycle event';
	END IF;

    -- A new pointer is a publication action and therefore must target exact
    -- active approved evidence.  An unchanged pointer may mirror an expired or
    -- archived approved revision so history remains identifiable while all read
    -- gates abstain.  Draft, pending, rejected, superseded, and rolled-back
    -- revisions can never become (or remain) the published pointer.
    IF TG_OP = 'INSERT' OR NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id THEN
        IF workflow.approval_status <> 'approved'
           OR workflow.lifecycle_status <> 'active'
           OR (workflow.expires_at IS NOT NULL AND workflow.expires_at <= clock_timestamp()) THEN
            RAISE EXCEPTION 'new current revision must resolve to an active approved evidence-backed workflow state';
        END IF;
    ELSIF workflow.approval_status <> 'approved'
       OR workflow.lifecycle_status NOT IN ('active', 'expired', 'archived') THEN
        RAISE EXCEPTION 'current revision must resolve to an approved active, expired, or archived workflow state';
    END IF;

    IF NEW.approval_status <> workflow.approval_status
       OR NEW.lifecycle_status <> workflow.lifecycle_status
       OR NEW.review_due_at IS DISTINCT FROM workflow.review_due_at
       OR NEW.expires_at IS DISTINCT FROM workflow.expires_at
       OR NEW.approved_by_sub IS DISTINCT FROM workflow.approved_by_sub
       OR NEW.approved_at IS DISTINCT FROM workflow.approved_at THEN
        RAISE EXCEPTION 'article governance must mirror its current revision workflow state';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS assert_article_governance_workflow_binding ON article_governance;
CREATE TRIGGER assert_article_governance_workflow_binding
    BEFORE INSERT OR UPDATE OF current_revision_id, workspace_id, classification,
	    read_policy, approval_status, lifecycle_status, review_due_at, expires_at,
	    approved_by_sub, approved_at, state_version
    ON article_governance
    FOR EACH ROW EXECUTE FUNCTION public.assert_article_governance_workflow_binding();

-- The outbox identity is immutable and an item can be created only for the
-- exact currently-published workflow version.  A vector worker must recheck
-- the same fence immediately before its replaceBySource(article:<id>, ...) call.
CREATE OR REPLACE FUNCTION public.assert_article_revision_reindex_outbox()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.article_id IS DISTINCT FROM OLD.article_id
           OR NEW.article_revision_id IS DISTINCT FROM OLD.article_revision_id
           OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
           OR NEW.classification IS DISTINCT FROM OLD.classification
           OR NEW.source_key IS DISTINCT FROM OLD.source_key
           OR NEW.governance_state_version IS DISTINCT FROM OLD.governance_state_version
           OR NEW.operation IS DISTINCT FROM OLD.operation
           OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'article reindex outbox identity is immutable';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.article_governance ag
        JOIN public.article_revision_workflow_states ws
          ON ws.article_revision_id = ag.current_revision_id
         AND ws.article_id = ag.article_id
         AND ws.workspace_id = ag.workspace_id
         AND ws.classification = ag.classification
        WHERE ag.article_id = NEW.article_id
          AND ag.current_revision_id = NEW.article_revision_id
          AND ag.workspace_id = NEW.workspace_id
          AND ag.classification = NEW.classification
          AND ag.state_version = NEW.governance_state_version
          AND ag.approval_status = 'approved'
          AND ag.lifecycle_status = 'active'
          AND (ag.expires_at IS NULL OR ag.expires_at > clock_timestamp())
          AND ws.approval_status = 'approved'
          AND ws.lifecycle_status = 'active'
          AND (ws.expires_at IS NULL OR ws.expires_at > clock_timestamp())
    ) THEN
        RAISE EXCEPTION 'reindex outbox item must be fenced to the current approved revision';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS assert_article_revision_reindex_outbox ON article_revision_reindex_outbox;
CREATE TRIGGER assert_article_revision_reindex_outbox
    BEFORE INSERT OR UPDATE ON article_revision_reindex_outbox
    FOR EACH ROW EXECUTE FUNCTION public.assert_article_revision_reindex_outbox();

-- Existing governed fixtures/connector records are an upgrade baseline, not a
-- new approval grant.  Preserve their existing approved pointer facts and make
-- every non-current approved revision historical/superseded.  New trusted
-- registrations must explicitly insert a workflow row before moving the
-- pointer; no automatic public-registration trigger is introduced here.
WITH legacy AS (
    SELECT ar.id AS article_revision_id,
           ar.article_id,
           ar.workspace_id,
           ar.classification,
           ar.created_at,
           ar.created_by_sub,
           ag.current_revision_id,
           ag.approval_status AS legacy_approval_status,
           ag.lifecycle_status AS legacy_lifecycle_status,
           ag.review_due_at AS legacy_review_due_at,
           ag.expires_at AS legacy_expires_at,
           ag.approved_by_sub AS legacy_approved_by_sub,
           ag.approved_at AS legacy_approved_at,
           ar.status AS revision_status
    FROM article_revisions ar
    LEFT JOIN article_governance ag
      ON ag.article_id = ar.article_id
     AND ag.workspace_id = ar.workspace_id
     AND ag.classification = ar.classification
    WHERE EXISTS (
        SELECT 1 FROM article_evidence_snapshots es
        WHERE es.article_revision_id = ar.id
    )
), normalized AS (
    SELECT legacy.*,
           CASE
               -- A legacy approved/superseded projection is terminal rather
               -- than a readable approved historical pointer.
               WHEN current_revision_id = article_revision_id
                    AND (legacy_approval_status = 'superseded'
                         OR legacy_lifecycle_status = 'superseded') THEN 'superseded'
               WHEN current_revision_id = article_revision_id THEN legacy_approval_status
               WHEN revision_status = 'approved' THEN 'superseded'
               ELSE revision_status
           END AS approval_status,
           CASE
               WHEN current_revision_id = article_revision_id
                    AND legacy_approval_status = 'approved'
                    AND legacy_lifecycle_status = 'active'
                    AND legacy_expires_at IS NOT NULL
                    AND legacy_expires_at <= clock_timestamp() THEN 'expired'
               WHEN current_revision_id = article_revision_id
                    AND legacy_approval_status = 'superseded' THEN 'superseded'
               WHEN current_revision_id = article_revision_id
                    AND legacy_approval_status IN ('draft', 'pending_review', 'rejected', 'rolled_back') THEN 'archived'
               WHEN current_revision_id = article_revision_id
                    AND legacy_lifecycle_status = 'superseded' THEN 'superseded'
               WHEN current_revision_id = article_revision_id
                    AND legacy_lifecycle_status IN ('active', 'expired', 'archived')
                    THEN legacy_lifecycle_status
               WHEN revision_status IN ('approved', 'superseded') THEN 'superseded'
               ELSE 'archived'
           END AS lifecycle_status
    FROM legacy
)
INSERT INTO article_revision_workflow_states (
    article_revision_id, article_id, workspace_id, classification,
    approval_status, lifecycle_status, review_due_at, expires_at,
    approved_by_sub, approved_at, state_version, created_at, updated_at)
SELECT article_revision_id,
       article_id,
       workspace_id,
       classification,
       approval_status,
       lifecycle_status,
       CASE
           WHEN current_revision_id = article_revision_id
                AND legacy_review_due_at IS NOT NULL
                AND legacy_expires_at IS NOT NULL
                AND legacy_review_due_at > legacy_expires_at
               THEN legacy_expires_at
           WHEN current_revision_id = article_revision_id THEN legacy_review_due_at
       END,
       CASE WHEN current_revision_id = article_revision_id THEN legacy_expires_at END,
       CASE WHEN approval_status = 'approved'
            THEN coalesce(legacy_approved_by_sub, 'system:migration', created_by_sub) END,
       CASE WHEN approval_status = 'approved'
            THEN coalesce(legacy_approved_at, created_at) END,
       0,
       created_at,
       created_at
FROM normalized
ON CONFLICT (article_revision_id) DO NOTHING;

INSERT INTO article_revision_lifecycle_events (
    id, article_revision_id, article_id, workspace_id, classification,
    event_type, from_approval_status, to_approval_status,
    from_lifecycle_status, to_lifecycle_status,
    expected_state_version, resulting_state_version, governance_state_version, actor_sub, metadata, occurred_at)
SELECT 'lifecycle:baseline:' || ws.article_revision_id,
       ws.article_revision_id, ws.article_id, ws.workspace_id, ws.classification,
       'migration_baseline', NULL, ws.approval_status, NULL, ws.lifecycle_status,
       -1, ws.state_version,
       CASE WHEN ag.current_revision_id = ws.article_revision_id THEN ag.state_version + 1 ELSE 0 END,
       'system:migration',
       jsonb_build_object('migration', '20260905040000_knowledge_lifecycle'),
       ws.created_at
FROM article_revision_workflow_states ws
LEFT JOIN article_governance ag
  ON ag.current_revision_id = ws.article_revision_id
 AND ag.article_id = ws.article_id
 AND ag.workspace_id = ws.workspace_id
 AND ag.classification = ws.classification
WHERE ws.state_version = 0
ON CONFLICT DO NOTHING;

-- Normalize the mutable governance projection to the newly materialized
-- workflow state in the same migration.  This both preserves historical
-- identity for approved expired rows and makes legacy rejected/superseded
-- pointer states fail closed rather than becoming a malformed future write.
-- The baseline event above is deliberately inserted first because the
-- governance trigger requires one event per consumed state version.
UPDATE article_governance ag
SET current_revision_id = CASE
                               WHEN ws.approval_status = 'approved'
                                AND ws.lifecycle_status IN ('active', 'expired', 'archived')
                                   THEN ws.article_revision_id
                               ELSE NULL
                          END,
    approval_status = ws.approval_status,
    lifecycle_status = ws.lifecycle_status,
    review_due_at = ws.review_due_at,
    expires_at = ws.expires_at,
    approved_by_sub = ws.approved_by_sub,
    approved_at = ws.approved_at,
    state_version = ag.state_version + 1
FROM article_revision_workflow_states ws
WHERE ag.current_revision_id = ws.article_revision_id
  AND ag.article_id = ws.article_id
  AND ag.workspace_id = ws.workspace_id
  AND ag.classification = ws.classification;

-- Do not relabel preexisting vector text as current evidence.  Instead, every
-- baseline currently-published revision gets a fenced reindex request.  Until
-- a worker builds canonical chunks from the immutable current snapshot/evidence
-- and stamps exact revision/evidence/span metadata, a strict vector predicate
-- must abstain from those old chunks rather than return stale text.
INSERT INTO article_revision_reindex_outbox (
    id, article_id, article_revision_id, workspace_id, classification,
    source_key, governance_state_version, operation, state)
SELECT 'reindex:baseline:' || ag.article_id || ':' || ag.state_version,
       ag.article_id,
       ag.current_revision_id,
       ag.workspace_id,
       ag.classification,
       'article:' || ag.article_id,
       ag.state_version,
       'replace',
       'pending'
FROM article_governance ag
JOIN article_revision_workflow_states ws
  ON ws.article_revision_id = ag.current_revision_id
 AND ws.article_id = ag.article_id
 AND ws.workspace_id = ag.workspace_id
 AND ws.classification = ag.classification
WHERE ag.current_revision_id IS NOT NULL
  AND ag.approval_status = 'approved'
  AND ag.lifecycle_status = 'active'
  AND (ag.expires_at IS NULL OR ag.expires_at > clock_timestamp())
  AND ws.approval_status = 'approved'
  AND ws.lifecycle_status = 'active'
  AND (ws.expires_at IS NULL OR ws.expires_at > clock_timestamp())
  AND ws.state_version = 0
ON CONFLICT (article_id, governance_state_version) DO NOTHING;

-- State rows and their ledger event are written together, but application
-- code deliberately writes the state before the event so the event trigger
-- can validate the resulting version.  Make the reverse invariant deferred:
-- at commit, every inserted or transitioned workflow version must have one
-- exact immutable event.  This closes the direct-SQL gap without weakening
-- the normal transaction ordering or the upgrade baseline above.
CREATE OR REPLACE FUNCTION public.assert_article_revision_workflow_event_binding()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.article_revision_lifecycle_events e
        WHERE e.article_revision_id = NEW.article_revision_id
          AND e.article_id = NEW.article_id
          AND e.workspace_id = NEW.workspace_id
          AND e.classification = NEW.classification
          AND e.resulting_state_version = NEW.state_version
          AND e.to_approval_status = NEW.approval_status
          AND e.to_lifecycle_status = NEW.lifecycle_status
    ) THEN
        RAISE EXCEPTION 'workflow state version requires an exact lifecycle event';
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS assert_article_revision_workflow_event_binding
    ON article_revision_workflow_states;
CREATE CONSTRAINT TRIGGER assert_article_revision_workflow_event_binding
    AFTER INSERT OR UPDATE ON article_revision_workflow_states
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION public.assert_article_revision_workflow_event_binding();

-- Review records are historical decisions, not a mutable workflow cache.
CREATE OR REPLACE FUNCTION public.reject_article_lifecycle_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    RAISE EXCEPTION 'article lifecycle history is immutable';
END;
$$;

DROP TRIGGER IF EXISTS immutable_article_reviews ON article_reviews;
CREATE TRIGGER immutable_article_reviews
    BEFORE UPDATE OR DELETE ON article_reviews
    FOR EACH ROW EXECUTE FUNCTION public.reject_article_lifecycle_history_mutation();

DROP TRIGGER IF EXISTS immutable_article_revision_lifecycle_events
    ON article_revision_lifecycle_events;
CREATE TRIGGER immutable_article_revision_lifecycle_events
    BEFORE UPDATE OR DELETE ON article_revision_lifecycle_events
    FOR EACH ROW EXECUTE FUNCTION public.reject_article_lifecycle_history_mutation();

-- Article evidence citations must now bind to the live workflow projection, not
-- the immutable creation-time ar.status.  This lets an approved draft become
-- readable without mutating its revision/evidence and invalidates citations on
-- reject, supersession, expiry, or rollback.
CREATE OR REPLACE FUNCTION public.validate_message_citation_reference_binding()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.article_governance ag
        JOIN public.article_revisions ar
          ON ar.id = ag.current_revision_id
         AND ar.article_id = ag.article_id
         AND ar.workspace_id = ag.workspace_id
         AND ar.classification = ag.classification
        JOIN public.article_revision_workflow_states ws
          ON ws.article_revision_id = ar.id
         AND ws.article_id = ar.article_id
         AND ws.workspace_id = ar.workspace_id
         AND ws.classification = ar.classification
        JOIN public.article_evidence_snapshots es
          ON es.id = NEW.evidence_snapshot_id
         AND es.article_revision_id = ar.id
        WHERE ag.article_id = NEW.article_id
          AND ar.id = NEW.article_revision_id
          AND ag.approval_status = 'approved'
          AND ag.lifecycle_status = 'active'
          AND (ag.expires_at IS NULL OR ag.expires_at > clock_timestamp())
          AND ws.approval_status = 'approved'
          AND ws.lifecycle_status = 'active'
          AND (ws.expires_at IS NULL OR ws.expires_at > clock_timestamp())
          AND NEW.source_span_end <= char_length(es.content)
          AND NEW.source_uri IS NOT DISTINCT FROM es.source_uri
          AND NEW.source_version IS NOT DISTINCT FROM es.source_version
    ) THEN
        RAISE EXCEPTION 'citation reference must resolve to current approved exact workflow evidence';
    END IF;
    RETURN NEW;
END;
$$;

-- The Spring API owns all lifecycle writes.  New tables/functions follow the
-- same deny-direct-Data-API posture as the earlier governance migration.
ALTER TABLE article_revision_workflow_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE article_revision_lifecycle_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE article_revision_reindex_outbox ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE article_revision_workflow_states,
    article_revision_lifecycle_events, article_revision_reindex_outbox
    FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assert_article_revision_lineage()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assert_article_revision_workflow_state()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assert_article_revision_lifecycle_event()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assert_article_governance_workflow_binding()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assert_article_revision_reindex_outbox()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.assert_article_revision_workflow_event_binding()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.reject_article_lifecycle_history_mutation()
    FROM PUBLIC, anon, authenticated;
