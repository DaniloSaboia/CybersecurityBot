-- ====================================================================
-- Sentinel KMS — Supabase / Postgres Database Schema Migration
-- Compatible with both local Supabase and hosted Supabase Cloud projects
-- ====================================================================

-- 1. Enable required extensions
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

-- 2. Categories
CREATE TABLE IF NOT EXISTS categories (
    id TEXT PRIMARY KEY,
    label TEXT NOT NULL,
    abbr TEXT NOT NULL,
    doc_count INTEGER DEFAULT 0,
    sort_order INTEGER DEFAULT 0
);

-- 3. Connectors
CREATE TABLE IF NOT EXISTS connectors (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    docs INTEGER DEFAULT 0,
    last_label TEXT,
    auto BOOLEAN DEFAULT FALSE,
    config JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 4. Ingest Jobs
CREATE TABLE IF NOT EXISTS ingest_jobs (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    source TEXT NOT NULL,
    size_label TEXT,
    stage TEXT NOT NULL,
    pct DOUBLE PRECISION DEFAULT 0.0,
    chunks INTEGER DEFAULT 0,
    when_label TEXT,
    error TEXT,
    owner_sub TEXT,
    source_url TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- 5. Articles (Knowledge Base)
CREATE TABLE IF NOT EXISTS articles (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    category_id TEXT REFERENCES categories(id),
    severity TEXT NOT NULL,
    tags TEXT[] DEFAULT '{}',
    source TEXT,
    updated_on DATE,
    author TEXT,
    confidence TEXT,
    reads INTEGER DEFAULT 0,
    cvss TEXT,
    snippet TEXT,
    body JSONB DEFAULT '[]'::jsonb,
    iocs JSONB DEFAULT '[]'::jsonb,
    related TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- 6. Conversations (Assistant Chat Threads)
CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY,
    user_sub TEXT NOT NULL,
    title TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- 7. Chat Messages
CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    citations TEXT[] DEFAULT '{}',
    rating INTEGER,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 8. Spring AI Vector Store (pgvector 1024-dim)
CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT,
    metadata JSONB,
    embedding extensions.vector(1024),
    content_tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content, ''))) STORED
);

-- 9. Indexes for Performance & Search
CREATE INDEX IF NOT EXISTS idx_articles_category ON articles(category_id);
CREATE INDEX IF NOT EXISTS idx_articles_severity ON articles(severity);
CREATE INDEX IF NOT EXISTS idx_articles_updated_on ON articles(updated_on DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_sub);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_ingest_jobs_owner ON ingest_jobs(owner_sub);

-- HNSW Vector Index (Cosine Distance)
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding 
    ON vector_store USING hnsw (embedding extensions.vector_cosine_ops);

-- Full-Text Search GIN Index
CREATE INDEX IF NOT EXISTS idx_vector_store_tsv 
    ON vector_store USING gin(content_tsv);

-- Metadata JSONB GIN Index
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata 
    ON vector_store USING gin(metadata);

-- 10. Keep mutable timestamps accurate when rows are updated outside JPA.
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS set_articles_updated_at ON articles;
CREATE TRIGGER set_articles_updated_at
    BEFORE UPDATE ON articles
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ingest_jobs_updated_at ON ingest_jobs;
CREATE TRIGGER set_ingest_jobs_updated_at
    BEFORE UPDATE ON ingest_jobs
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_conversations_updated_at ON conversations;
CREATE TRIGGER set_conversations_updated_at
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 11. The browser uses Supabase Auth only; application data is served by the
-- authenticated Spring API. Deny direct Data API access and enable RLS as
-- defense in depth for every table in the exposed public schema.
ALTER TABLE categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE connectors ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingest_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE articles ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE vector_store ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE categories, connectors, ingest_jobs, articles,
    conversations, messages, vector_store FROM anon, authenticated;
