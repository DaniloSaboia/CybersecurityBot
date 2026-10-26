-- Explicit operational evaluator grants. Workspace role alone never authorizes
-- GoldenSet viewing or retrieval evaluation execution.

CREATE TABLE IF NOT EXISTS public.evaluation_operational_grants (
    id TEXT PRIMARY KEY CHECK (nullif(btrim(id), '') IS NOT NULL),
    subject TEXT NOT NULL CHECK (nullif(btrim(subject), '') IS NOT NULL),
    workspace_id TEXT NOT NULL REFERENCES public.workspaces(id) ON DELETE RESTRICT,
    capability TEXT NOT NULL CHECK (capability IN ('dataset_view', 'execution')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_evaluation_operational_grants_active
    ON public.evaluation_operational_grants (subject, workspace_id, capability)
    WHERE revoked_at IS NULL;

-- Dataset-view is an explicit active grant plus an active membership in the
-- same workspace. This is intentionally independent of membership role.
CREATE OR REPLACE FUNCTION public.can_view_evaluation_dataset(p_subject TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
    SELECT CASE
        WHEN nullif(btrim(p_subject), '') IS NULL THEN FALSE
        ELSE EXISTS (
            SELECT 1
            FROM public.evaluation_operational_grants grant_row
            JOIN public.workspace_memberships membership
              ON membership.workspace_id = grant_row.workspace_id
             AND membership.subject = grant_row.subject
             AND membership.active = TRUE
            WHERE grant_row.subject = p_subject
              AND grant_row.capability = 'dataset_view'
              AND grant_row.revoked_at IS NULL
        )
    END;
$$;

-- Execution requires an active execution grant and its dataset-view
-- prerequisite in the exact same active membership workspace.
CREATE OR REPLACE FUNCTION public.can_execute_evaluation(p_subject TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
    SELECT CASE
        WHEN nullif(btrim(p_subject), '') IS NULL THEN FALSE
        ELSE EXISTS (
            SELECT 1
            FROM public.evaluation_operational_grants execution_grant
            JOIN public.evaluation_operational_grants view_grant
              ON view_grant.subject = execution_grant.subject
             AND view_grant.workspace_id = execution_grant.workspace_id
             AND view_grant.capability = 'dataset_view'
             AND view_grant.revoked_at IS NULL
            JOIN public.workspace_memberships membership
              ON membership.workspace_id = execution_grant.workspace_id
             AND membership.subject = execution_grant.subject
             AND membership.active = TRUE
            WHERE execution_grant.subject = p_subject
              AND execution_grant.capability = 'execution'
              AND execution_grant.revoked_at IS NULL
        )
    END;
$$;

-- Grant identity and context are append-only. A grant may be revoked once,
-- but can never be altered, restored, or deleted.
CREATE OR REPLACE FUNCTION public.reject_evaluation_operational_grant_mutation()
RETURNS trigger
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'evaluation operational grants are immutable';
    END IF;

    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.subject IS DISTINCT FROM NEW.subject
       OR OLD.workspace_id IS DISTINCT FROM NEW.workspace_id
       OR OLD.capability IS DISTINCT FROM NEW.capability
       OR OLD.granted_at IS DISTINCT FROM NEW.granted_at
       OR OLD.metadata IS DISTINCT FROM NEW.metadata
       OR OLD.revoked_at IS NOT NULL
       OR NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'evaluation operational grants may only be revoked once';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS immutable_evaluation_operational_grants ON public.evaluation_operational_grants;
CREATE TRIGGER immutable_evaluation_operational_grants
    BEFORE UPDATE OR DELETE ON public.evaluation_operational_grants
    FOR EACH ROW EXECUTE FUNCTION public.reject_evaluation_operational_grant_mutation();

ALTER TABLE public.evaluation_operational_grants ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.evaluation_operational_grants FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.can_view_evaluation_dataset(TEXT) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.can_execute_evaluation(TEXT) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.reject_evaluation_operational_grant_mutation() FROM PUBLIC, anon, authenticated;
