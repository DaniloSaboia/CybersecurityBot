-- ============================================================================
-- Sentinel KMS — initial schema
-- Source of truth for the database. The Spring Boot backend runs with
-- hibernate ddl-auto=validate, so these column names/types are the contract.
-- ============================================================================

-- ---- Extensions ------------------------------------------------------------
create extension if not exists vector;     -- pgvector (Supabase ships this)
create extension if not exists pgcrypto;   -- gen_random_uuid()

-- ---- updated_at helper -----------------------------------------------------
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- ============================================================================
-- Categories  (TI / POL / IR / PB / CVE / GRC)
-- ============================================================================
create table if not exists public.categories (
  id          text primary key,
  label       text        not null,
  abbr        text        not null,
  doc_count   integer     not null default 0,
  sort_order  integer     not null default 0
);

-- ============================================================================
-- Articles  (the knowledge base entries)
-- ============================================================================
create table if not exists public.articles (
  id          text primary key,                 -- e.g. "TI-2291", "CVE-2024-21413"
  title       text        not null,
  category_id text        not null references public.categories (id),
  severity    text        not null
              check (severity in ('critical','high','medium','low','info')),
  tags        text[]      not null default '{}',
  source      text        not null default '',
  updated_on  date        not null default current_date,
  author      text        not null default '',
  confidence  text        not null default '',
  reads       integer     not null default 0,
  cvss        text,                              -- only for CVE advisories
  snippet     text        not null default '',
  body        jsonb       not null default '[]'::jsonb,  -- [{ "h": ..., "p": ... }]
  iocs        jsonb       not null default '[]'::jsonb,  -- [{ "type": ..., "value": ... }]
  related     text[]      not null default '{}',         -- related article ids
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index if not exists articles_category_idx on public.articles (category_id);
create index if not exists articles_severity_idx on public.articles (severity);
create index if not exists articles_updated_idx  on public.articles (updated_on desc);
create index if not exists articles_reads_idx     on public.articles (reads desc);
create index if not exists articles_tags_idx      on public.articles using gin (tags);

drop trigger if exists trg_articles_updated_at on public.articles;
create trigger trg_articles_updated_at before update on public.articles
  for each row execute function public.set_updated_at();

-- ============================================================================
-- Source connectors  (S3 / API / URL feeds)
-- ============================================================================
create table if not exists public.connectors (
  id          text primary key,
  name        text        not null,
  kind        text        not null check (kind in ('S3','API','URL')),
  status      text        not null default 'connected'
              check (status in ('connected','syncing','error')),
  docs        integer     not null default 0,
  last_label  text        not null default '',     -- "4m ago" | "syncing…" | "auth failed"
  auto        boolean     not null default true,
  config      jsonb       not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);

-- ============================================================================
-- Ingestion jobs  (documents moving through the pipeline)
-- ============================================================================
create table if not exists public.ingest_jobs (
  id          text primary key,                 -- "job-7741"
  name        text        not null,
  source      text        not null default 'Manual upload',
  size_label  text        not null default '',
  stage       text        not null default 'queued'
              check (stage in ('queued','extracting','chunking','embedding','indexed','failed')),
  pct         numeric(5,2) not null default 0,
  chunks      integer     not null default 0,
  when_label  text        not null default 'now',
  error       text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index if not exists ingest_jobs_stage_idx   on public.ingest_jobs (stage);
create index if not exists ingest_jobs_created_idx on public.ingest_jobs (created_at desc);

drop trigger if exists trg_ingest_jobs_updated_at on public.ingest_jobs;
create trigger trg_ingest_jobs_updated_at before update on public.ingest_jobs
  for each row execute function public.set_updated_at();

-- ============================================================================
-- Vector store  (chunk embeddings — schema matches Spring AI PgVectorStore)
--   metadata holds: article_id, job_id, source, heading, chunk_index, ...
-- ============================================================================
create table if not exists public.vector_store (
  id        uuid primary key default gen_random_uuid(),
  content   text,
  metadata  jsonb,
  embedding vector(1024)
);

create index if not exists vector_store_embedding_hnsw_idx
  on public.vector_store using hnsw (embedding vector_cosine_ops);

create index if not exists vector_store_metadata_idx
  on public.vector_store using gin (metadata);
