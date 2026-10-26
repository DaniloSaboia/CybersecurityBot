-- ============================================================================
-- Durable ingestion: retained sources, durable claims, generation fencing.
--
-- New job IDs remain canonical UUID strings stored in the existing TEXT column.
-- Legacy seeded job-* ids are deliberately preserved for compatibility.
-- ============================================================================

-- 1. Durable job state.  New rows default to demos until the real ingestion
-- service explicitly marks them false, so a newly introduced direct insert can
-- never accidentally be picked up by the real worker.
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS source_id TEXT;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS source_revision_id TEXT;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS source_key TEXT;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS index_generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS claim_token TEXT;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS claimed_by TEXT;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS claim_until TIMESTAMPTZ;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;
ALTER TABLE ingest_jobs ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- A job's immutable vector identity is server-derived provenance, not a
-- caller-controlled metadata value.  Legacy rows are intentionally allowed to
-- retain NULL identity until they are deleted or recreated through the durable
-- pipeline.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ingest_jobs_source_key_matches_identity'
          AND conrelid = 'public.ingest_jobs'::regclass
    ) THEN
        ALTER TABLE ingest_jobs
            ADD CONSTRAINT ingest_jobs_source_key_matches_identity
            CHECK (
                source_key IS NULL
                OR (
                    source_id IS NOT NULL
                    AND source_revision_id IS NOT NULL
                    AND source_key = source_id || ':' || source_revision_id
                )
            );
    END IF;
END;
$$;

-- Existing user-owned in-flight rows predate retained source material.  Do not
-- pretend they are restartable: fail them honestly and require a new upload or
-- import.  Seeded rows remain explicit demos.
UPDATE ingest_jobs
SET is_demo = FALSE
WHERE owner_sub IS NOT NULL;

UPDATE ingest_jobs
SET stage = 'failed',
    pct = 0,
    error = 'Legacy job source was not retained; upload or import again',
    claim_token = NULL,
    claimed_by = NULL,
    claimed_at = NULL,
    claim_until = NULL
WHERE is_demo = FALSE
  AND source_id IS NULL
  AND stage IN ('queued', 'extracting', 'chunking', 'embedding');

CREATE UNIQUE INDEX IF NOT EXISTS uq_ingest_jobs_source_key
    ON ingest_jobs (source_key)
    WHERE source_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ingest_jobs_claimable
    ON ingest_jobs (created_at, claim_until)
    WHERE deleted_at IS NULL
      AND is_demo = FALSE
      AND stage IN ('queued', 'extracting', 'chunking', 'embedding');

CREATE INDEX IF NOT EXISTS idx_ingest_jobs_live_owner
    ON ingest_jobs (owner_sub, created_at DESC)
    WHERE deleted_at IS NULL;

-- 2. Source material is kept outside the job row so job listings never load
-- BLOBs.  The database cap matches the service default and current multipart
-- maximum (20 MiB); application configuration may lower, but not raise, it.
CREATE TABLE IF NOT EXISTS ingest_job_sources (
    id TEXT PRIMARY KEY,
    job_id TEXT NOT NULL UNIQUE REFERENCES ingest_jobs(id) ON DELETE CASCADE,
    source_type TEXT NOT NULL CHECK (source_type IN ('upload', 'url')),
    source_revision_id TEXT NOT NULL,
    source_key TEXT NOT NULL UNIQUE,
    original_name TEXT,
    content_type TEXT,
    source_url TEXT,
    source_bytes BYTEA,
    source_size_bytes BIGINT NOT NULL DEFAULT 0
        CHECK (source_size_bytes >= 0 AND source_size_bytes <= 20971520),
    source_sha256 TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ingest_job_sources_key_matches_identity
        CHECK (source_key = id || ':' || source_revision_id),
    CONSTRAINT ingest_job_sources_url_has_url
        CHECK (source_type = 'upload' OR source_url IS NOT NULL),
    CONSTRAINT ingest_job_sources_byte_integrity
        CHECK (
            (source_bytes IS NULL AND source_size_bytes = 0 AND source_sha256 IS NULL)
            OR
            (source_bytes IS NOT NULL
             AND octet_length(source_bytes) = source_size_bytes
             AND source_sha256 ~ '^[0-9a-f]{64}$')
        )
);

CREATE INDEX IF NOT EXISTS idx_ingest_job_sources_job ON ingest_job_sources (job_id);

DROP TRIGGER IF EXISTS set_ingest_job_sources_updated_at ON ingest_job_sources;
CREATE TRIGGER set_ingest_job_sources_updated_at
    BEFORE UPDATE ON ingest_job_sources
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Source identity/revision, its derived key, and the original request
-- descriptor are provenance facts.  A content retry increments
-- index_generation rather than mutating this identity or retargeting an
-- unfetched URL revision.
CREATE OR REPLACE FUNCTION public.prevent_durable_ingest_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    IF NEW.source_id IS DISTINCT FROM OLD.source_id
       OR NEW.source_revision_id IS DISTINCT FROM OLD.source_revision_id
       OR NEW.source_key IS DISTINCT FROM OLD.source_key THEN
        RAISE EXCEPTION 'durable ingestion source identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS prevent_ingest_job_identity_mutation ON ingest_jobs;
CREATE TRIGGER prevent_ingest_job_identity_mutation
    BEFORE UPDATE ON ingest_jobs
    FOR EACH ROW EXECUTE FUNCTION public.prevent_durable_ingest_identity_mutation();

CREATE OR REPLACE FUNCTION public.prevent_durable_source_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.source_revision_id IS DISTINCT FROM OLD.source_revision_id
       OR NEW.source_key IS DISTINCT FROM OLD.source_key
       OR NEW.job_id IS DISTINCT FROM OLD.job_id
       OR NEW.source_type IS DISTINCT FROM OLD.source_type
       OR NEW.source_url IS DISTINCT FROM OLD.source_url
       OR NEW.original_name IS DISTINCT FROM OLD.original_name
       OR NEW.content_type IS DISTINCT FROM OLD.content_type THEN
        RAISE EXCEPTION 'durable ingestion source identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS prevent_ingest_source_identity_mutation ON ingest_job_sources;
CREATE TRIGGER prevent_ingest_source_identity_mutation
    BEFORE UPDATE ON ingest_job_sources
    FOR EACH ROW EXECUTE FUNCTION public.prevent_durable_source_identity_mutation();

-- URL imports begin with no bytes, then retain the first successful response.
-- That null -> bytes transition is the only permitted content mutation for a
-- revision.  Without this guard, a later retry or out-of-band update could
-- silently change the material indexed under an immutable source_key.
CREATE OR REPLACE FUNCTION public.prevent_durable_source_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    IF OLD.source_bytes IS NOT NULL
       AND (
           NEW.source_bytes IS DISTINCT FROM OLD.source_bytes
           OR NEW.source_size_bytes IS DISTINCT FROM OLD.source_size_bytes
           OR NEW.source_sha256 IS DISTINCT FROM OLD.source_sha256
       ) THEN
        RAISE EXCEPTION 'durable ingestion source content is write-once';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS prevent_ingest_source_content_mutation ON ingest_job_sources;
CREATE TRIGGER prevent_ingest_source_content_mutation
    BEFORE UPDATE ON ingest_job_sources
    FOR EACH ROW EXECUTE FUNCTION public.prevent_durable_source_content_mutation();

-- 3. Follow the existing "Spring API only" posture for raw retained sources.
ALTER TABLE ingest_job_sources ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE ingest_job_sources FROM anon, authenticated;
