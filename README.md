# Sentinel KMS — SecOps Knowledge Management System

A cybersecurity knowledge console with three surfaces:

1. **Assistant** — a RAG chatbot that answers security questions grounded in the knowledge base, with inline citations.
2. **Knowledge Base** — a searchable library of security documents (threat intel, policies, postmortems, playbooks, CVE advisories, compliance frameworks).
3. **Ingestion** — a document pipeline (Ingest → Extract → Chunk → Embed → Index) with source connectors and live job status into a vector store.

**Stack:** Spring Boot 3.4 (Java 21) · React 18 + Vite · Supabase Postgres + **pgvector** · [Spring AI](https://docs.spring.io/spring-ai/reference/) with **pluggable Mistral _or_ local Ollama** providers. Everything runs in Docker — no local JDK/Node required.

---

## Architecture

```
                 ┌────────────────────────────────────────────────────┐
  Browser  ──▶   │  frontend (Vite/React, :5173)                      │
                 │   proxies /api ──▶ backend                         │
                 └───────────────┬────────────────────────────────────┘
                                 │ /api
                 ┌───────────────▼────────────────────────────────────┐
                 │  backend (Spring Boot, :8080)                       │
                 │   ├─ kb         REST over articles/categories       │
                 │   ├─ ingest     upload→extract(Tika)→chunk→embed    │
                 │   ├─ assistant  RAG: retrieve → ground → cite       │
                 │   └─ vectorstore  Spring AI VectorStore (pgvector)  │
                 │        provider = Mistral │ Ollama  (by profile)    │
                 └───────────────┬───────────────────┬────────────────┘
                                 │ JDBC              │ embeddings/chat
                 ┌───────────────▼─────────┐   ┌─────▼──────────────┐
                 │ Supabase Postgres        │   │ Mistral API   OR   │
                 │  + pgvector (:54322)     │   │ Ollama (:11434)    │
                 │  (run via Supabase CLI)  │   └────────────────────┘
                 └──────────────────────────┘
```

Embeddings are **1024-dim** so the same `vector(1024)` schema works for both `mistral-embed` (Mistral) and `mxbai-embed-large` (Ollama). Switching providers with a different dimension means editing the migration and re-embedding.

---

## Prerequisites

- **Docker Desktop** (you have it) — runs the backend, frontend, and optional Ollama.
- **Supabase CLI** — runs local Postgres + pgvector + Studio. Install:
  `scoop install supabase` · `npm i -g supabase` · or see https://supabase.com/docs/guides/cli
- (Optional) A **Mistral API key** from https://console.mistral.ai if you want cloud inference instead of local.

> No local JDK, Maven, or Node needed — they run inside the build containers.

---

## Quick start

### 1. Start the database (Supabase CLI)

```bash
cd CybersecurityBot
supabase start
```

This boots Postgres on **:54322** (user/pass `postgres`/`postgres`), the API on :54321, and Studio on :54323, and applies the migration in `supabase/migrations/` (extensions, tables, the `vector_store` table + HNSW index).

> If `supabase start` warns about a config version mismatch, run `supabase init` once to regenerate `supabase/config.toml` for your CLI version — it leaves the migrations untouched — then `supabase start` again.

### 2. Configure environment

```bash
cp .env.example .env
```

Then pick your provider in `.env`:

- **Mistral (cloud):** set `SPRING_PROFILES_ACTIVE=mistral` and `MISTRAL_API_KEY=...`
- **Local (Ollama):** set `SPRING_PROFILES_ACTIVE=local`

### 3. Run the app

**Mistral mode:**
```bash
docker compose up --build
```

**Local mode (Ollama):**
```bash
SPRING_PROFILES_ACTIVE=local docker compose --profile local up --build
# one-time: pull the models
docker compose exec ollama ollama pull llama3.1
docker compose exec ollama ollama pull mxbai-embed-large
```
(On Windows PowerShell, set the var first: `$env:SPRING_PROFILES_ACTIVE="local"` then run the compose command.)

### 4. Open it

- App: **http://localhost:5173**
- API docs (Swagger): **http://localhost:8080/swagger-ui.html**
- Supabase Studio: **http://localhost:54323**

On first boot the backend seeds the knowledge base from `backend/src/main/resources/seed/seed.json` (8 security articles, categories, connectors, demo jobs) and embeds the articles into pgvector — so the KB is populated and the Assistant is genuinely grounded out of the box.

---

## API surface

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/me` | session identity / tenant |
| GET | `/api/kb/categories` | category list + counts |
| GET | `/api/kb/articles?q=&category=&severity=&sort=` | filtered/sorted articles |
| GET | `/api/kb/articles/{id}` | full article (body, IOCs, related) |
| POST | `/api/assistant/chat` | RAG answer (blocks + citations) |
| GET | `/api/assistant/suggestions` | starter prompts |
| POST | `/api/assistant/context` | attach context (stub) |
| GET | `/api/ingest/jobs` | ingestion queue (poll for live status) |
| POST | `/api/ingest/upload` | upload a file (multipart) |
| POST | `/api/ingest/url` | ingest from a URL |
| POST | `/api/ingest/jobs/{id}/retry` | retry a failed job |
| GET | `/api/connectors` | source connectors |
| GET | `/api/vectorstore/status` | engine, model, chunk count, dim, health |

The chat response is an ordered array of typed **blocks** (`text`, `steps`, `callout`, `report`) plus `citations` (article ids). Inline `[ARTICLE-ID]` tokens render as clickable citation chips. See `docs/` and the in-repo `README` from the design bundle for the full contract.

---

## Project layout

```
CybersecurityBot/
├─ docker-compose.yml         backend + frontend (+ optional ollama)
├─ .env.example               provider + DB + RAG config
├─ supabase/
│  ├─ config.toml             local Supabase CLI config
│  └─ migrations/             schema (tables, pgvector, indexes)
├─ backend/                   Spring Boot
│  ├─ Dockerfile              multi-stage Maven build
│  └─ src/main/java/com/sentinel/kms/
│     ├─ kb/                  knowledge base (articles, categories)
│     ├─ ingest/             pipeline (extract→chunk→embed→index, connectors)
│     ├─ assistant/          RAG (retrieve → ground → cited blocks)
│     ├─ vectorstore/        Spring AI VectorStore seam over pgvector
│     ├─ seed/               startup seeder (loads + embeds seed.json)
│     ├─ config/  common/    CORS, OpenAPI, error handling, /api/me
│     └─ resources/          application*.yml, seed/seed.json
└─ frontend/                  Vite + React port of the prototype
   ├─ Dockerfile
   └─ src/  (App, views/, ui, icons, lib/api, lib/queries, index.css)
```

---

## Switching / extending providers

Provider selection is pure config (Spring AI's `spring.ai.model.{chat,embedding}` switch):

- `application-mistral.yml` → Mistral chat + embeddings
- `application-local.yml` → Ollama chat + embeddings

To add another OpenAI-compatible provider, add the matching `spring-ai-starter-model-*` dependency and a profile. If its embedding dimension ≠ 1024, update `vector(1024)` in the migration, `spring.ai.vectorstore.pgvector.dimensions`, and re-embed.

## Notes & next steps

- **Auth** is stubbed (`/api/me` returns a fixed analyst). Wire Supabase Auth / a JWT filter for real sessions.
- **Chat** is request/response today; streaming (SSE token stream) is a natural follow-up.
- **Connectors** are display + seed only; implementing real S3/MISP/NVD sync is future work.
- Hibernate `ddl-auto` is `none` (the migration is the source of truth). Tighten to `validate` once confirmed for your Postgres.
