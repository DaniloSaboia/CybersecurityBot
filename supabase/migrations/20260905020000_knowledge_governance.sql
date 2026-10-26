-- ============================================================================
-- Sentinel KMS — governed knowledge, immutable evidence, authorization, audit
-- ============================================================================
-- This migration is deliberately application-policy friendly: the Spring API
-- still applies predicates for each request because its database owner may
-- bypass RLS.  The rows below are the authoritative source of memberships,
-- classifications, approval state, revisions, and evidence provenance.

-- 1. Workspaces and trusted memberships.  No guessed administrator is seeded:
-- administrators/editors must be provisioned by a trusted server-side process.
CREATE TABLE IF NOT EXISTS workspaces (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO workspaces (id, name)
VALUES ('shared', 'Shared threat feed')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS workspace_memberships (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    subject TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'editor', 'viewer')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, subject)
);

CREATE INDEX IF NOT EXISTS idx_workspace_memberships_subject
    ON workspace_memberships (subject, workspace_id) WHERE active = TRUE;
CREATE INDEX IF NOT EXISTS idx_workspace_memberships_workspace_role
    ON workspace_memberships (workspace_id, role) WHERE active = TRUE;

DROP TRIGGER IF EXISTS set_workspace_memberships_updated_at ON workspace_memberships;
CREATE TRIGGER set_workspace_memberships_updated_at
    BEFORE UPDATE ON workspace_memberships
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 2. Published article policy is normalized away from mutable article metadata.
CREATE TABLE IF NOT EXISTS article_governance (
    article_id TEXT PRIMARY KEY REFERENCES articles(id) ON DELETE RESTRICT,
    workspace_id TEXT NOT NULL REFERENCES workspaces(id),
    classification TEXT NOT NULL CHECK (classification IN ('public', 'internal', 'confidential', 'restricted')),
    approval_status TEXT NOT NULL CHECK (approval_status IN
        ('draft', 'pending_review', 'approved', 'rejected', 'superseded', 'rolled_back')),
    read_policy TEXT NOT NULL CHECK (read_policy IN
        ('public', 'workspace_member', 'workspace_editor', 'workspace_admin')),
    lifecycle_status TEXT NOT NULL DEFAULT 'active' CHECK (lifecycle_status IN
        ('active', 'superseded', 'expired', 'archived')),
    current_revision_id TEXT,
    review_due_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_by_sub TEXT NOT NULL,
    approved_by_sub TEXT,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_article_governance_visibility
    ON article_governance (workspace_id, classification, approval_status, lifecycle_status);

DROP TRIGGER IF EXISTS set_article_governance_updated_at ON article_governance;
CREATE TRIGGER set_article_governance_updated_at
    BEFORE UPDATE ON article_governance
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 3. Immutable versions and exact immutable evidence snapshots.
CREATE TABLE IF NOT EXISTS article_revisions (
    id TEXT PRIMARY KEY,
    article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE RESTRICT,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    workspace_id TEXT NOT NULL REFERENCES workspaces(id),
    classification TEXT NOT NULL CHECK (classification IN ('public', 'internal', 'confidential', 'restricted')),
    status TEXT NOT NULL CHECK (status IN
        ('draft', 'pending_review', 'approved', 'rejected', 'superseded', 'rolled_back')),
    snapshot JSONB NOT NULL,
    content_sha256 TEXT NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    supersedes_revision_id TEXT REFERENCES article_revisions(id) ON DELETE RESTRICT,
    rollback_of_revision_id TEXT REFERENCES article_revisions(id) ON DELETE RESTRICT,
    created_by_sub TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (article_id, revision_number)
);

CREATE INDEX IF NOT EXISTS idx_article_revisions_article_number
    ON article_revisions (article_id, revision_number DESC);

CREATE TABLE IF NOT EXISTS article_evidence_snapshots (
    id TEXT PRIMARY KEY,
    article_revision_id TEXT NOT NULL REFERENCES article_revisions(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    source_uri TEXT,
    source_label TEXT NOT NULL,
    source_version TEXT,
    content TEXT NOT NULL,
    content_sha256 TEXT NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    created_by_sub TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (article_revision_id, ordinal)
);

CREATE INDEX IF NOT EXISTS idx_article_evidence_revision_ordinal
    ON article_evidence_snapshots (article_revision_id, ordinal);

CREATE TABLE IF NOT EXISTS article_reviews (
    id TEXT PRIMARY KEY,
    article_revision_id TEXT NOT NULL REFERENCES article_revisions(id) ON DELETE RESTRICT,
    decision TEXT NOT NULL CHECK (decision IN
        ('submitted', 'approved', 'rejected', 'changes_requested', 'superseded', 'rolled_back')),
    reviewer_sub TEXT NOT NULL,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_article_reviews_revision ON article_reviews (article_revision_id, created_at DESC);

-- Backfill every already-visible article into a stable public/approved baseline.
-- This is an upgrade-only compatibility action; future unclassified inserts are
-- handled by the fail-closed bootstrap trigger below.
INSERT INTO article_governance (
    article_id, workspace_id, classification, approval_status, read_policy,
    lifecycle_status, created_by_sub, approved_by_sub, approved_at)
SELECT a.id, 'shared', 'public', 'approved', 'public', 'active',
       'system:migration', 'system:migration', now()
FROM articles a
ON CONFLICT (article_id) DO NOTHING;

WITH revision_snapshots AS (
    SELECT a.id AS article_id,
           'rev:' || a.id || ':1' AS revision_id,
           jsonb_build_object(
               'id', a.id,
               'title', a.title,
               'categoryId', a.category_id,
               'severity', a.severity,
               'tags', to_jsonb(coalesce(a.tags, ARRAY[]::TEXT[])),
               'source', a.source,
               'updatedOn', a.updated_on,
               'author', a.author,
               'confidence', a.confidence,
               'reads', a.reads,
               'cvss', a.cvss,
               'snippet', a.snippet,
               'body', coalesce(a.body, '[]'::jsonb),
               'iocs', coalesce(a.iocs, '[]'::jsonb),
               'related', to_jsonb(coalesce(a.related, ARRAY[]::TEXT[]))
           ) AS snapshot
    FROM articles a
)
INSERT INTO article_revisions (
    id, article_id, revision_number, workspace_id, classification, status,
    snapshot, content_sha256, created_by_sub)
SELECT revision_id, article_id, 1, 'shared', 'public', 'approved', snapshot,
       encode(extensions.digest(convert_to(snapshot::text, 'UTF8'), 'sha256'), 'hex'),
       'system:migration'
FROM revision_snapshots
ON CONFLICT (id) DO NOTHING;

UPDATE article_governance ag
SET current_revision_id = 'rev:' || ag.article_id || ':1'
WHERE ag.current_revision_id IS NULL
  AND EXISTS (SELECT 1 FROM article_revisions ar
              WHERE ar.id = 'rev:' || ag.article_id || ':1');

-- Baseline passage 0 mirrors the first legacy vector chunk; subsequent passages
-- mirror each body section, giving existing citations exact source-version spans.
WITH evidence_rows AS (
    SELECT a.id AS article_id,
           'rev:' || a.id || ':1' AS revision_id,
           0 AS ordinal,
           coalesce(a.source, 'Legacy source') AS source_label,
           a.updated_on::text AS source_version,
           coalesce(a.title, '') || ' — ' || coalesce(a.snippet, '') AS content
    FROM articles a
    UNION ALL
    SELECT a.id AS article_id,
           'rev:' || a.id || ':1' AS revision_id,
           body.ordinality::integer AS ordinal,
           coalesce(a.source, 'Legacy source') AS source_label,
           a.updated_on::text AS source_version,
           coalesce(body.section->>'h', '') || E'\n' || coalesce(body.section->>'p', '') AS content
    FROM articles a
    CROSS JOIN LATERAL jsonb_array_elements(coalesce(a.body, '[]'::jsonb))
         WITH ORDINALITY AS body(section, ordinality)
)
INSERT INTO article_evidence_snapshots (
    id, article_revision_id, ordinal, source_label, source_version, content,
    content_sha256, created_by_sub)
SELECT 'evidence:' || article_id || ':1:' || ordinal, revision_id, ordinal,
       source_label, source_version, content,
       encode(extensions.digest(convert_to(content, 'UTF8'), 'sha256'), 'hex'),
       'system:migration'
FROM evidence_rows
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_article_governance_current_revision'
    ) THEN
        ALTER TABLE article_governance
            ADD CONSTRAINT fk_article_governance_current_revision
            FOREIGN KEY (current_revision_id) REFERENCES article_revisions(id) ON DELETE RESTRICT;
    END IF;
END $$;

-- A revision ID by itself is not sufficient provenance: it could point at a
-- revision for another article, workspace, or classification.  The composite
-- key makes those facts one database-enforced binding, rather than relying on
-- every reader to remember all three predicates.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_article_revisions_governance_binding'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT uq_article_revisions_governance_binding
            UNIQUE (id, article_id, workspace_id, classification);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_article_governance_current_revision_binding'
          AND conrelid = 'public.article_governance'::regclass
    ) THEN
        ALTER TABLE article_governance
            ADD CONSTRAINT fk_article_governance_current_revision_binding
            FOREIGN KEY (current_revision_id, article_id, workspace_id, classification)
            REFERENCES article_revisions (id, article_id, workspace_id, classification)
            ON DELETE RESTRICT;
    END IF;
END $$;

-- New source rows receive no implicit public policy.  Only the explicit
-- TrustedArticleRegistrationService may register seed and known connector
-- producers as public; an unregistered article has no governance row and is
-- hidden by every read predicate (fail closed).

-- 4. Ingestion policy. Owner-private data is protected by the persisted owner;
-- null owner never means shared unless the explicit backfilled scope says so.
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS workspace_id TEXT REFERENCES workspaces(id);
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS access_scope TEXT NOT NULL DEFAULT 'owner_private'
    CHECK (access_scope IN ('owner_private', 'workspace', 'public_shared'));
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS classification TEXT NOT NULL DEFAULT 'restricted'
    CHECK (classification IN ('public', 'internal', 'confidential', 'restricted'));

UPDATE ingest_jobs
SET workspace_id = 'shared', access_scope = 'public_shared', classification = 'public'
WHERE owner_sub IS NULL AND is_demo = TRUE;

UPDATE ingest_jobs
SET access_scope = 'owner_private', classification = 'restricted'
WHERE owner_sub IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ingest_jobs_governed_scope
    ON ingest_jobs (workspace_id, access_scope, classification)
    WHERE deleted_at IS NULL;

-- Database-side visibility predicate shared by retrieval and any direct
-- source lookup.  It deliberately grants nothing from a NULL owner: public
-- visibility requires both server-owned scope and public classification, and
-- workspace visibility requires an active persisted membership.
CREATE OR REPLACE FUNCTION public.can_read_ingest_job(p_job_id TEXT, p_subject TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
    SELECT CASE
        WHEN nullif(btrim(p_job_id), '') IS NULL OR nullif(btrim(p_subject), '') IS NULL
            THEN FALSE
        ELSE EXISTS (
            SELECT 1
            FROM public.ingest_jobs ij
            WHERE ij.id = p_job_id
              AND ij.deleted_at IS NULL
              AND ij.cancelled_at IS NULL
              AND (
                  ij.owner_sub = p_subject
                  OR (ij.access_scope = 'public_shared' AND ij.classification = 'public')
                  OR (
                      ij.access_scope = 'workspace'
                      AND EXISTS (
                          SELECT 1
                          FROM public.workspace_memberships wm
                          WHERE wm.workspace_id = ij.workspace_id
                            AND wm.subject = p_subject
                            AND wm.active = TRUE
                            AND (
                                ij.classification IN ('public', 'internal')
                                OR (ij.classification = 'confidential'
                                    AND wm.role IN ('owner', 'admin', 'editor'))
                                OR (ij.classification = 'restricted'
                                    AND wm.role IN ('owner', 'admin'))
                            )
                      )
                  )
              )
        )
    END;
$$;

REVOKE EXECUTE ON FUNCTION public.can_read_ingest_job(TEXT, TEXT)
    FROM PUBLIC, anon, authenticated;

-- Exact durable source identity is distinct from a job label or mutable
-- vector metadata.  This helper lets message-history readers re-evaluate a
-- stored upload reference against the live server-owned job policy.
CREATE OR REPLACE FUNCTION public.can_read_ingest_source(
    p_source_id TEXT,
    p_source_revision_id TEXT,
    p_source_key TEXT,
    p_subject TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
    SELECT CASE
        WHEN nullif(btrim(p_source_id), '') IS NULL
          OR nullif(btrim(p_source_revision_id), '') IS NULL
          OR nullif(btrim(p_source_key), '') IS NULL
          OR nullif(btrim(p_subject), '') IS NULL
            THEN FALSE
        ELSE EXISTS (
            SELECT 1
            FROM public.ingest_jobs ij
            WHERE ij.source_id = p_source_id
              AND ij.source_revision_id = p_source_revision_id
              AND ij.source_key = p_source_key
              AND ij.source_key = ij.source_id || ':' || ij.source_revision_id
              AND ij.stage = 'indexed'
              AND public.can_read_ingest_job(ij.id, p_subject)
        )
    END;
$$;

REVOKE EXECUTE ON FUNCTION public.can_read_ingest_source(TEXT, TEXT, TEXT, TEXT)
    FROM PUBLIC, anon, authenticated;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_ingest_jobs_message_source_binding'
          AND conrelid = 'public.ingest_jobs'::regclass
    ) THEN
        ALTER TABLE ingest_jobs
            ADD CONSTRAINT uq_ingest_jobs_message_source_binding
            UNIQUE (source_id, source_revision_id, source_key);
    END IF;
END $$;

-- Connector workspace is server-owned data, not connector.config JSON.
ALTER TABLE connectors ADD COLUMN IF NOT EXISTS workspace_id TEXT REFERENCES workspaces(id);
UPDATE connectors SET workspace_id = 'shared' WHERE workspace_id IS NULL;
ALTER TABLE connectors ALTER COLUMN workspace_id SET DEFAULT 'shared';

-- 5. Backfill safe provenance metadata for legacy article vectors. Visibility
-- remains derived from joined governance rows, never this mutable JSON metadata.
UPDATE vector_store vs
SET metadata = coalesce(vs.metadata, '{}'::jsonb) || jsonb_build_object(
    'source_key', 'article:' || ag.article_id,
    'article_revision_id', ag.current_revision_id,
    'revision_id', ag.current_revision_id,
    'article_revision_number', ar.revision_number,
    'revision_status', ar.status)
FROM article_governance ag
JOIN article_revisions ar ON ar.id = ag.current_revision_id
WHERE vs.metadata ? 'article_id'
  AND vs.metadata->>'article_id' = ag.article_id;

CREATE INDEX IF NOT EXISTS idx_vector_store_source_key
    ON vector_store ((metadata->>'source_key'));
CREATE INDEX IF NOT EXISTS idx_vector_store_article_revision
    ON vector_store ((metadata->>'article_revision_id'));

-- 6. Append-only access/change audit.
CREATE TABLE IF NOT EXISTS knowledge_audit_events (
    id TEXT PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_sub TEXT NOT NULL,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    workspace_id TEXT REFERENCES workspaces(id),
    article_id TEXT REFERENCES articles(id) ON DELETE RESTRICT,
    article_revision_id TEXT REFERENCES article_revisions(id) ON DELETE RESTRICT,
    outcome TEXT NOT NULL CHECK (outcome IN ('allowed', 'denied', 'success', 'failure')),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_knowledge_audit_workspace_time
    ON knowledge_audit_events (workspace_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_audit_article_time
    ON knowledge_audit_events (article_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_knowledge_audit_actor_time
    ON knowledge_audit_events (actor_sub, occurred_at DESC);

-- 7. Source-backed graph.  Relations are asserted/manual/legacy, never marked
-- as inferred correctness.  Each edge carries the source revision/evidence.
CREATE TABLE IF NOT EXISTS knowledge_entities (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces(id),
    entity_type TEXT NOT NULL,
    canonical_name TEXT NOT NULL,
    normalized_value TEXT NOT NULL,
    classification TEXT NOT NULL CHECK (classification IN ('public', 'internal', 'confidential', 'restricted')),
    created_by_sub TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, entity_type, normalized_value)
);

CREATE TABLE IF NOT EXISTS knowledge_relations (
    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL REFERENCES workspaces(id),
    subject_entity_id TEXT NOT NULL REFERENCES knowledge_entities(id) ON DELETE RESTRICT,
    predicate TEXT NOT NULL,
    object_entity_id TEXT NOT NULL REFERENCES knowledge_entities(id) ON DELETE RESTRICT,
    article_revision_id TEXT NOT NULL REFERENCES article_revisions(id) ON DELETE RESTRICT,
    evidence_snapshot_id TEXT REFERENCES article_evidence_snapshots(id) ON DELETE RESTRICT,
    passage_start INTEGER NOT NULL DEFAULT 0 CHECK (passage_start >= 0),
    passage_end INTEGER NOT NULL DEFAULT 0 CHECK (passage_end >= passage_start),
    provenance_kind TEXT NOT NULL CHECK (provenance_kind IN ('manual', 'source_asserted', 'legacy_related')),
    created_by_sub TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_relations_subject ON knowledge_relations (subject_entity_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_relations_object ON knowledge_relations (object_entity_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_relations_revision ON knowledge_relations (article_revision_id);

INSERT INTO knowledge_entities (
    id, workspace_id, entity_type, canonical_name, normalized_value, classification, created_by_sub)
SELECT 'article:' || a.id, ag.workspace_id, 'article', a.title, lower(a.id), ag.classification,
       'system:migration'
FROM articles a
JOIN article_governance ag ON ag.article_id = a.id
ON CONFLICT (workspace_id, entity_type, normalized_value) DO NOTHING;

INSERT INTO knowledge_relations (
    id, workspace_id, subject_entity_id, predicate, object_entity_id,
    article_revision_id, evidence_snapshot_id, passage_start, passage_end,
    provenance_kind, created_by_sub)
SELECT 'relation:' || a.id || ':' || related.article_id,
       ag.workspace_id, 'article:' || a.id, 'RELATED_TO', 'article:' || related.article_id,
       ag.current_revision_id, 'evidence:' || a.id || ':1:0', 0, 0,
       'legacy_related', 'system:migration'
FROM articles a
JOIN article_governance ag ON ag.article_id = a.id
CROSS JOIN LATERAL unnest(coalesce(a.related, ARRAY[]::TEXT[])) AS related(article_id)
JOIN articles target ON target.id = related.article_id
ON CONFLICT (id) DO NOTHING;

-- 8. Optional immutable provenance for chat messages. Existing messages.citations
-- remains the backward-compatible article-id list; this table is the exact form.
CREATE TABLE IF NOT EXISTS message_citation_references (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    article_id TEXT NOT NULL REFERENCES articles(id) ON DELETE RESTRICT,
    article_revision_id TEXT NOT NULL REFERENCES article_revisions(id) ON DELETE RESTRICT,
    evidence_snapshot_id TEXT NOT NULL REFERENCES article_evidence_snapshots(id) ON DELETE RESTRICT,
    source_span_start INTEGER NOT NULL CHECK (source_span_start >= 0),
    source_span_end INTEGER NOT NULL CHECK (source_span_end >= source_span_start),
    source_uri TEXT,
    source_version TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, ordinal)
);

CREATE INDEX IF NOT EXISTS idx_message_citation_references_message
    ON message_citation_references (message_id, ordinal);

-- Non-article uploads are grounded by durable source identity rather than an
-- article/evidence snapshot.  These rows intentionally contain no user-
-- editable scope or owner fields; their live policy is always re-resolved
-- through ingest_jobs and workspace memberships.
CREATE TABLE IF NOT EXISTS message_source_references (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    source_id TEXT NOT NULL,
    source_revision_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key = source_id || ':' || source_revision_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (message_id, ordinal),
    CONSTRAINT fk_message_source_reference_identity
        FOREIGN KEY (source_id, source_revision_id, source_key)
        REFERENCES ingest_jobs (source_id, source_revision_id, source_key)
        ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_message_source_references_message
    ON message_source_references (message_id, ordinal);

-- The message's owning subject, not a caller-supplied owner/scope, is the
-- authorization input.  This permits persisted public/workspace uploads and
-- rejects private, tombstoned, cancelled, stale, or forged source identities.
CREATE OR REPLACE FUNCTION public.validate_message_source_reference_binding()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.messages m
        JOIN public.conversations c ON c.id = m.conversation_id
        JOIN public.ingest_jobs ij
          ON ij.source_id = NEW.source_id
         AND ij.source_revision_id = NEW.source_revision_id
         AND ij.source_key = NEW.source_key
         AND ij.source_key = ij.source_id || ':' || ij.source_revision_id
        WHERE m.id = NEW.message_id
          AND m.role = 'assistant'
          AND ij.stage = 'indexed'
          AND public.can_read_ingest_source(
              NEW.source_id, NEW.source_revision_id, NEW.source_key, c.user_sub)
    ) THEN
        RAISE EXCEPTION 'message source reference must resolve to a readable live canonical source';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS validate_message_source_reference_binding ON message_source_references;
CREATE TRIGGER validate_message_source_reference_binding
    BEFORE INSERT ON message_source_references
    FOR EACH ROW EXECUTE FUNCTION public.validate_message_source_reference_binding();

-- A citation must bind all three immutable identities together.  Individual
-- primary-key FKs are insufficient: they would permit article A to cite a
-- revision or evidence snapshot belonging to article B.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_article_revisions_citation_binding'
          AND conrelid = 'public.article_revisions'::regclass
    ) THEN
        ALTER TABLE article_revisions
            ADD CONSTRAINT uq_article_revisions_citation_binding UNIQUE (id, article_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_article_evidence_citation_binding'
          AND conrelid = 'public.article_evidence_snapshots'::regclass
    ) THEN
        ALTER TABLE article_evidence_snapshots
            ADD CONSTRAINT uq_article_evidence_citation_binding UNIQUE (id, article_revision_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_message_citation_reference_revision_article'
          AND conrelid = 'public.message_citation_references'::regclass
    ) THEN
        ALTER TABLE message_citation_references
            ADD CONSTRAINT fk_message_citation_reference_revision_article
            FOREIGN KEY (article_revision_id, article_id)
            REFERENCES article_revisions (id, article_id) ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_message_citation_reference_evidence_revision'
          AND conrelid = 'public.message_citation_references'::regclass
    ) THEN
        ALTER TABLE message_citation_references
            ADD CONSTRAINT fk_message_citation_reference_evidence_revision
            FOREIGN KEY (evidence_snapshot_id, article_revision_id)
            REFERENCES article_evidence_snapshots (id, article_revision_id) ON DELETE RESTRICT;
    END IF;
END $$;

-- The composite FKs prove identity, while this insert-time guard proves that
-- the immutable identity remains the article's current approved evidence and
-- that its recorded source fields and passage interval are exact.  It is a
-- backstop for application resolvers, never a substitute for subject access
-- checks performed before transcript persistence.
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
        JOIN public.article_evidence_snapshots es
          ON es.id = NEW.evidence_snapshot_id
         AND es.article_revision_id = ar.id
        WHERE ag.article_id = NEW.article_id
          AND ar.id = NEW.article_revision_id
          AND ag.approval_status = 'approved'
          AND ag.lifecycle_status = 'active'
          AND (ag.expires_at IS NULL OR ag.expires_at > now())
          AND ar.status = 'approved'
          AND NEW.source_span_end <= char_length(es.content)
          AND NEW.source_uri IS NOT DISTINCT FROM es.source_uri
          AND NEW.source_version IS NOT DISTINCT FROM es.source_version
    ) THEN
        RAISE EXCEPTION 'citation reference must resolve to current approved exact evidence';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS validate_message_citation_reference_binding ON message_citation_references;
CREATE TRIGGER validate_message_citation_reference_binding
    BEFORE INSERT ON message_citation_references
    FOR EACH ROW EXECUTE FUNCTION public.validate_message_citation_reference_binding();

-- Immutable history/audit rows cannot be rewritten or removed by application code.
CREATE OR REPLACE FUNCTION public.reject_governance_history_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    -- A user deleting a conversation already relies on FK cascade to remove
    -- its messages.  Preserve that supported lifecycle while refusing direct
    -- citation mutation/deletion: the FK's internal DELETE nests this trigger.
    IF TG_TABLE_NAME IN ('message_citation_references', 'message_source_references')
       AND TG_OP = 'DELETE'
       AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'governance history is immutable';
END;
$$;

DROP TRIGGER IF EXISTS immutable_article_revisions ON article_revisions;
CREATE TRIGGER immutable_article_revisions
    BEFORE UPDATE OR DELETE ON article_revisions
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();
DROP TRIGGER IF EXISTS immutable_article_evidence ON article_evidence_snapshots;
CREATE TRIGGER immutable_article_evidence
    BEFORE UPDATE OR DELETE ON article_evidence_snapshots
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();
DROP TRIGGER IF EXISTS immutable_knowledge_audit_events ON knowledge_audit_events;
CREATE TRIGGER immutable_knowledge_audit_events
    BEFORE UPDATE OR DELETE ON knowledge_audit_events
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();
DROP TRIGGER IF EXISTS immutable_message_citation_references ON message_citation_references;
CREATE TRIGGER immutable_message_citation_references
    BEFORE UPDATE OR DELETE ON message_citation_references
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();
DROP TRIGGER IF EXISTS immutable_message_source_references ON message_source_references;
CREATE TRIGGER immutable_message_source_references
    BEFORE UPDATE OR DELETE ON message_source_references
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();

-- 9. Same direct-data-API denial posture as the original schema.
ALTER TABLE workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE article_governance ENABLE ROW LEVEL SECURITY;
ALTER TABLE article_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE article_evidence_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE article_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_entities ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_relations ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_citation_references ENABLE ROW LEVEL SECURITY;
ALTER TABLE message_source_references ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE workspaces, workspace_memberships, article_governance,
    article_revisions, article_evidence_snapshots, article_reviews,
    knowledge_audit_events, knowledge_entities, knowledge_relations,
    message_citation_references, message_source_references FROM anon, authenticated;
