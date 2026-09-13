-- ============================================================================
-- Sentinel KMS — redacted, append-only audit hardening
-- ============================================================================
-- This migration deliberately replaces the audit table's generic historical
-- trigger with a dedicated guard.  It remains effective even when an upgrade
-- fixture (or a prior deployment) has removed immutable_knowledge_audit_events.

-- The old audit outcome check predates lifecycle concurrency conflicts.
ALTER TABLE public.knowledge_audit_events
    DROP CONSTRAINT IF EXISTS knowledge_audit_events_outcome_check;
ALTER TABLE public.knowledge_audit_events
    ADD CONSTRAINT knowledge_audit_events_outcome_check
    CHECK (outcome IN ('allowed', 'denied', 'success', 'failure', 'conflict')) NOT VALID;

-- New audit writes can only contain a small, typed scalar metadata vocabulary.
-- The predicate is deliberately not a generic JSON schema: no arbitrary text,
-- arrays, or nested objects can carry source content, credentials, prompts, or
-- exception details into an audit row.
CREATE OR REPLACE FUNCTION public.audit_metadata_is_safe(p_metadata JSONB)
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
SET search_path = pg_catalog
AS $$
    SELECT jsonb_typeof(p_metadata) = 'object'
       AND octet_length(p_metadata::text) <= 4096
       AND NOT EXISTS (
            SELECT 1
            FROM jsonb_each(p_metadata) AS entry(key, value)
            WHERE (CASE
                WHEN entry.key IN (
                    'expected_state_version', 'actual_state_version',
                    'size_bytes', 'attempt', 'generation', 'chunk_count',
                    'vector_rows_deleted', 'limit', 'returned_count',
                    -- Legacy access/retrieval counters remain readable.
                    'requested', 'allowed', 'matches', 'vectorCandidates',
                    'lexicalCandidates', 'returned'
                ) THEN jsonb_typeof(entry.value) = 'number'
                    AND (entry.value #>> '{}') ~ '^(0|[1-9][0-9]*)$'
                WHEN entry.key IN ('retained', 'idempotency_key_present', 'created')
                    THEN jsonb_typeof(entry.value) = 'boolean'
                WHEN entry.key IN (
                    'from_revision_id', 'to_revision_id', 'source_id', 'source_revision_id'
                ) THEN jsonb_typeof(entry.value) = 'string'
                    AND (entry.value #>> '{}') ~ '^[A-Za-z0-9._:-]{1,200}$'
                WHEN entry.key IN ('reason_code', 'failure_code')
                    THEN jsonb_typeof(entry.value) = 'string'
                    AND (entry.value #>> '{}') ~ '^[A-Z][A-Z0-9_]{0,79}$'
                WHEN entry.key IN ('review_due_at', 'expires_at')
                    THEN jsonb_typeof(entry.value) = 'string'
                    AND char_length(entry.value #>> '{}') <= 64
                    AND (entry.value #>> '{}')
                        ~ E'^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z$'
                WHEN entry.key = 'source_type'
                    THEN jsonb_typeof(entry.value) = 'string'
                    AND entry.value #>> '{}' IN ('upload', 'url')
                ELSE FALSE
            END) IS NOT TRUE
       );
$$;

-- Check constraints run as the writing role.  That would force every
-- non-owner backend writer to execute the validator function, defeating its
-- client-facing REVOKE.  The dedicated trigger below owns validation instead.
ALTER TABLE public.knowledge_audit_events
    DROP CONSTRAINT IF EXISTS knowledge_audit_events_redacted_fields_check;
ALTER TABLE public.knowledge_audit_events
    DROP CONSTRAINT IF EXISTS knowledge_audit_events_safe_metadata_check;

-- This is the sole write-time validation boundary.  It does not perform
-- dynamic SQL or table lookups, pins its search path to pg_catalog, and
-- qualifies its one internal helper.  SECURITY DEFINER lets an application
-- runtime role with INSERT use the guard without gaining direct EXECUTE on
-- the validator or authorization helpers.
CREATE OR REPLACE FUNCTION public.validate_knowledge_audit_event_insert()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.actor_sub !~ '^[A-Za-z0-9._:-]{1,200}$'
       OR NEW.action !~ '^[A-Z][A-Z0-9_]{0,79}$'
       OR NEW.resource_type !~ '^[a-z][a-z0-9_-]{0,63}$'
       OR NEW.resource_id !~ '^[A-Za-z0-9._:-]{1,200}$'
       OR (NEW.workspace_id IS NOT NULL
           AND NEW.workspace_id !~ '^[A-Za-z0-9._:-]{1,200}$')
       OR (NEW.article_id IS NOT NULL
           AND NEW.article_id !~ '^[A-Za-z0-9._:-]{1,200}$')
       OR (NEW.article_revision_id IS NOT NULL
           AND NEW.article_revision_id !~ '^[A-Za-z0-9._:-]{1,200}$') THEN
        RAISE EXCEPTION 'audit event contains invalid redacted fields'
            USING ERRCODE = '22023';
    END IF;

    IF public.audit_metadata_is_safe(NEW.metadata) IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'audit event metadata is not a permitted compact scalar object'
            USING ERRCODE = '22023';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS knowledge_audit_events_validate_insert ON public.knowledge_audit_events;
CREATE TRIGGER knowledge_audit_events_validate_insert
    BEFORE INSERT ON public.knowledge_audit_events
    FOR EACH ROW EXECUTE FUNCTION public.validate_knowledge_audit_event_insert();

-- Stable keyset pagination for the server-side, membership-authorized read API.
CREATE INDEX IF NOT EXISTS idx_knowledge_audit_workspace_cursor
    ON public.knowledge_audit_events (workspace_id, occurred_at DESC, id DESC)
    WHERE workspace_id IS NOT NULL;

-- A dedicated trigger provides the audit guarantee independently of the old
-- generic governance-history trigger and also blocks table-wide truncation.
CREATE OR REPLACE FUNCTION public.reject_knowledge_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'knowledge audit events are append-only'
        USING ERRCODE = '55000';
END;
$$;

DROP TRIGGER IF EXISTS immutable_knowledge_audit_events ON public.knowledge_audit_events;
DROP TRIGGER IF EXISTS knowledge_audit_events_append_only_row ON public.knowledge_audit_events;
CREATE TRIGGER knowledge_audit_events_append_only_row
    BEFORE UPDATE OR DELETE ON public.knowledge_audit_events
    FOR EACH ROW EXECUTE FUNCTION public.reject_knowledge_audit_event_mutation();

DROP TRIGGER IF EXISTS knowledge_audit_events_append_only_truncate ON public.knowledge_audit_events;
CREATE TRIGGER knowledge_audit_events_append_only_truncate
    BEFORE TRUNCATE ON public.knowledge_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION public.reject_knowledge_audit_event_mutation();

-- Authorization is derived at query time from persisted memberships.  This is
-- SECURITY INVOKER by design: it never bypasses RLS or grants a caller a role
-- based on user-editable request/JWT metadata.
CREATE OR REPLACE FUNCTION public.can_read_workspace_audit(p_workspace_id TEXT, p_subject TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
    SELECT CASE
        WHEN nullif(btrim(p_workspace_id), '') IS NULL
          OR nullif(btrim(p_subject), '') IS NULL THEN FALSE
        ELSE EXISTS (
            SELECT 1
            FROM public.workspace_memberships wm
            WHERE wm.workspace_id = p_workspace_id
              AND wm.subject = p_subject
              AND wm.active = TRUE
              AND wm.role IN ('owner', 'admin')
        )
    END;
$$;

-- The Spring backend is the only audit writer/reader.  Client-facing roles
-- receive neither direct table access nor callable authorization helpers.
ALTER TABLE public.knowledge_audit_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE public.knowledge_audit_events FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.audit_metadata_is_safe(JSONB)
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.validate_knowledge_audit_event_insert()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.reject_knowledge_audit_event_mutation()
    FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.can_read_workspace_audit(TEXT, TEXT)
    FROM PUBLIC, anon, authenticated;
