-- Additive governed graph. Article evidence only; no upload or vector fallback.
-- Rows are immutable assertions, not independently approved facts. Every API read
-- rechecks evidence authorization; inferred assertions remain explicitly marked.
CREATE TABLE knowledge_graph_entities (
    entity_type TEXT NOT NULL CHECK (entity_type ~ '^[a-z][a-z0-9_]{0,63}$'),
    label TEXT NOT NULL CHECK (length(btrim(label)) BETWEEN 1 AND 500),

    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL,
    classification TEXT NOT NULL,
    article_id TEXT NOT NULL,
    article_revision_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    evidence_snapshot_id TEXT NOT NULL REFERENCES article_evidence_snapshots(id) ON DELETE RESTRICT,
    source_span_start INTEGER NOT NULL CHECK (source_span_start >= 0),
    source_span_end INTEGER NOT NULL CHECK (source_span_end > source_span_start),
    source_uri TEXT,
    source_version TEXT,
    evidence_sha256 TEXT NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    statement_kind TEXT NOT NULL CHECK (statement_kind IN ('sourced', 'inferred')),
    inference_method TEXT,
    inference_version TEXT,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    record_version INTEGER NOT NULL DEFAULT 1 CHECK (record_version = 1),
    created_by_sub TEXT NOT NULL CHECK (length(btrim(created_by_sub)) > 0),
    CHECK ((statement_kind = 'sourced' AND inference_method IS NULL AND inference_version IS NULL)
        OR (statement_kind = 'inferred' AND length(btrim(inference_method)) BETWEEN 1 AND 200
            AND length(btrim(inference_version)) BETWEEN 1 AND 100
            AND inference_method IS NOT NULL AND inference_version IS NOT NULL)),
    FOREIGN KEY (article_revision_id, article_id, workspace_id, classification)
        REFERENCES article_revisions(id, article_id, workspace_id, classification) ON DELETE RESTRICT
,
    UNIQUE (id, workspace_id, classification)
);
CREATE TABLE knowledge_graph_edges (
    from_entity_id TEXT NOT NULL,
    to_entity_id TEXT NOT NULL,
    relation_type TEXT NOT NULL CHECK (relation_type ~ '^[a-z][a-z0-9_]{0,63}$'),

    id TEXT PRIMARY KEY,
    workspace_id TEXT NOT NULL,
    classification TEXT NOT NULL,
    article_id TEXT NOT NULL,
    article_revision_id TEXT NOT NULL,
    revision_number INTEGER NOT NULL CHECK (revision_number > 0),
    evidence_snapshot_id TEXT NOT NULL REFERENCES article_evidence_snapshots(id) ON DELETE RESTRICT,
    source_span_start INTEGER NOT NULL CHECK (source_span_start >= 0),
    source_span_end INTEGER NOT NULL CHECK (source_span_end > source_span_start),
    source_uri TEXT,
    source_version TEXT,
    evidence_sha256 TEXT NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    statement_kind TEXT NOT NULL CHECK (statement_kind IN ('sourced', 'inferred')),
    inference_method TEXT,
    inference_version TEXT,
    observed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    record_version INTEGER NOT NULL DEFAULT 1 CHECK (record_version = 1),
    created_by_sub TEXT NOT NULL CHECK (length(btrim(created_by_sub)) > 0),
    CHECK ((statement_kind = 'sourced' AND inference_method IS NULL AND inference_version IS NULL)
        OR (statement_kind = 'inferred' AND length(btrim(inference_method)) BETWEEN 1 AND 200
            AND length(btrim(inference_version)) BETWEEN 1 AND 100
            AND inference_method IS NOT NULL AND inference_version IS NOT NULL)),
    FOREIGN KEY (article_revision_id, article_id, workspace_id, classification)
        REFERENCES article_revisions(id, article_id, workspace_id, classification) ON DELETE RESTRICT
,
    FOREIGN KEY (from_entity_id, workspace_id, classification)
        REFERENCES knowledge_graph_entities(id, workspace_id, classification) ON DELETE RESTRICT,
    FOREIGN KEY (to_entity_id, workspace_id, classification)
        REFERENCES knowledge_graph_entities(id, workspace_id, classification) ON DELETE RESTRICT
);
CREATE INDEX idx_graph_entities_evidence ON knowledge_graph_entities(evidence_snapshot_id);
CREATE INDEX idx_graph_entities_revision ON knowledge_graph_entities(article_revision_id);
CREATE INDEX idx_graph_edges_evidence ON knowledge_graph_edges(evidence_snapshot_id);
CREATE INDEX idx_graph_edges_revision ON knowledge_graph_edges(article_revision_id);
CREATE INDEX idx_graph_edges_from ON knowledge_graph_edges(from_entity_id);
CREATE INDEX idx_graph_edges_to ON knowledge_graph_edges(to_entity_id);
ALTER TABLE knowledge_graph_entities ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_graph_edges ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON knowledge_graph_entities, knowledge_graph_edges FROM PUBLIC, anon, authenticated;
-- Graph access is through the authenticated Spring API. No direct client policy.
CREATE TRIGGER graph_entities_immutable BEFORE UPDATE OR DELETE ON knowledge_graph_entities
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();
CREATE TRIGGER graph_edges_immutable BEFORE UPDATE OR DELETE ON knowledge_graph_edges
    FOR EACH ROW EXECUTE FUNCTION public.reject_governance_history_mutation();

-- This binding uses Vellum's current workflow projection, never ar.status.
-- Subject visibility remains KnowledgeAccessService + EvidenceReferenceResolver
-- at the Spring boundary. Direct client table/function privileges are revoked.
CREATE FUNCTION public.assert_graph_evidence_binding() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.article_governance ag
        JOIN public.article_revisions ar ON ar.id = ag.current_revision_id
          AND ar.article_id = ag.article_id AND ar.workspace_id = ag.workspace_id
          AND ar.classification = ag.classification
        JOIN public.article_revision_workflow_states ws ON ws.article_revision_id = ar.id
          AND ws.article_id = ar.article_id AND ws.workspace_id = ar.workspace_id
          AND ws.classification = ar.classification
        JOIN public.article_evidence_snapshots es ON es.article_revision_id = ar.id
        WHERE ag.approval_status = 'approved' AND ag.lifecycle_status = 'active'
          AND (ag.expires_at IS NULL OR ag.expires_at > clock_timestamp())
          AND ws.approval_status = 'approved' AND ws.lifecycle_status = 'active'
          AND (ws.expires_at IS NULL OR ws.expires_at > clock_timestamp())
          AND ar.article_id = NEW.article_id AND ar.id = NEW.article_revision_id
          AND ar.revision_number = NEW.revision_number
          AND ar.workspace_id = NEW.workspace_id AND ar.classification = NEW.classification
          AND es.id = NEW.evidence_snapshot_id
          AND NEW.source_span_start >= 0 AND NEW.source_span_end > NEW.source_span_start
          AND NEW.source_span_end <= char_length(es.content)
          AND es.source_uri IS NOT DISTINCT FROM NEW.source_uri
          AND es.source_version IS NOT DISTINCT FROM NEW.source_version
          AND es.content_sha256 = NEW.evidence_sha256
    ) THEN
        RAISE EXCEPTION 'graph evidence unavailable' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'knowledge_graph_edges' THEN
        IF EXISTS (
            SELECT 1 FROM public.knowledge_graph_entities ge
            WHERE ge.id IN (NEW.from_entity_id, NEW.to_entity_id)
              AND NOT EXISTS (
                SELECT 1 FROM public.article_governance ag
                JOIN public.article_revision_workflow_states ws
                  ON ws.article_revision_id = ag.current_revision_id
                  AND ws.article_id = ag.article_id AND ws.workspace_id = ag.workspace_id
                  AND ws.classification = ag.classification
                WHERE ag.article_id = ge.article_id AND ag.current_revision_id = ge.article_revision_id
                  AND ag.workspace_id = ge.workspace_id AND ag.classification = ge.classification
                  AND ag.approval_status = 'approved' AND ag.lifecycle_status = 'active'
                  AND (ag.expires_at IS NULL OR ag.expires_at > clock_timestamp())
                  AND ws.approval_status = 'approved' AND ws.lifecycle_status = 'active'
                  AND (ws.expires_at IS NULL OR ws.expires_at > clock_timestamp())
              )
        ) THEN
            RAISE EXCEPTION 'graph endpoint evidence unavailable' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER graph_entities_evidence_binding BEFORE INSERT ON knowledge_graph_entities
    FOR EACH ROW EXECUTE FUNCTION public.assert_graph_evidence_binding();
CREATE TRIGGER graph_edges_evidence_binding BEFORE INSERT ON knowledge_graph_edges
    FOR EACH ROW EXECUTE FUNCTION public.assert_graph_evidence_binding();
REVOKE EXECUTE ON FUNCTION public.assert_graph_evidence_binding() FROM PUBLIC, anon, authenticated;
