-- ============================================================================
-- Request idempotency for durable user uploads and URL imports.
--
-- The mapping is created in the same transaction as its job/source.  Its
-- deferred foreign key lets the server reserve the unique owner/key pair before
-- persisting the job, while rollback removes the reservation on every failure.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ingest_request_idempotency (
    id TEXT PRIMARY KEY,
    owner_sub TEXT NOT NULL CHECK (btrim(owner_sub) <> ''),
    idempotency_key TEXT NOT NULL
        CHECK (char_length(idempotency_key) BETWEEN 1 AND 200),
    request_kind TEXT NOT NULL CHECK (request_kind IN ('upload', 'url')),
    payload_sha256 TEXT NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    job_id TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ingest_request_idempotency_owner_key
        UNIQUE (owner_sub, idempotency_key),
    CONSTRAINT fk_ingest_request_idempotency_job
        FOREIGN KEY (job_id) REFERENCES ingest_jobs(id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX IF NOT EXISTS idx_ingest_request_idempotency_created
    ON ingest_request_idempotency (created_at DESC);

-- A replay mapping is evidence of the original accepted request.  It must never
-- be retargeted to a different job or silently rewritten with a new payload.
CREATE OR REPLACE FUNCTION public.prevent_ingest_request_idempotency_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.owner_sub IS DISTINCT FROM OLD.owner_sub
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.request_kind IS DISTINCT FROM OLD.request_kind
       OR NEW.payload_sha256 IS DISTINCT FROM OLD.payload_sha256
       OR NEW.job_id IS DISTINCT FROM OLD.job_id THEN
        RAISE EXCEPTION 'ingest request idempotency mapping is immutable';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS prevent_ingest_request_idempotency_mutation
    ON ingest_request_idempotency;
CREATE TRIGGER prevent_ingest_request_idempotency_mutation
    BEFORE UPDATE ON ingest_request_idempotency
    FOR EACH ROW EXECUTE FUNCTION public.prevent_ingest_request_idempotency_mutation();

-- The Spring application is the only reader/writer of request fingerprints and
-- job mappings.  Browser clients never receive raw payload fingerprints.
ALTER TABLE ingest_request_idempotency ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE ingest_request_idempotency FROM anon, authenticated;
