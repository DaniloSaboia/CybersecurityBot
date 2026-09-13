-- Backward-compatible transcript projection of immutable evidence provenance.
-- Legacy messages.citations remains the display-oriented article-id array.
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS citation_references JSONB NOT NULL DEFAULT '[]'::jsonb;
