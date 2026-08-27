# Sentinel KMS — SecOps Knowledge Management System

A cybersecurity knowledge console with three surfaces:

1. **Assistant** — a RAG chatbot that answers security questions grounded in the knowledge base, with inline citations, multi-turn conversation memory, IOC-aware lookups, and answer feedback.
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

Embeddings are **1024-dim** so the same `vector(1024)` schema works for both `mistral-embed` (Mistral) and `mxbai-embed-large` (Ollama). Switching providers with a different dimension means editing the migration and re-embedding..

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
| POST | `/api/assistant/chat/stream` | the same answer, streamed as SSE |
| GET | `/api/assistant/suggestions` | starter prompts |
| GET | `/api/assistant/conversations` | user's chat threads, newest first |
| GET | `/api/assistant/conversations/{id}/messages` | full transcript of a thread |
| DELETE | `/api/assistant/conversations/{id}` | delete a thread (cascades messages) |
| POST | `/api/assistant/messages/{id}/feedback` | rate an answer (±1, 0 = clear) |
| GET | `/api/ingest/jobs` | ingestion queue (own + demo jobs; poll for live status) |
| POST | `/api/ingest/upload` | upload a file (multipart; extension allowlist enforced) |
| POST | `/api/ingest/url` | ingest from a URL (SSRF-guarded, fetched for real) |
| POST | `/api/ingest/jobs/{id}/retry` | retry a failed job (own jobs) |
| DELETE | `/api/ingest/jobs/{id}` | delete an own job + its embeddings |
| GET | `/api/connectors` | source connectors |
| POST | `/api/connectors/nvd/sync?days=&max=` | pull recent CVEs from NVD into the KB |
| GET | `/api/vectorstore/status` | engine, model, chunk count, dim, health |

The chat response is an ordered array of typed **blocks** (`text`, `steps`, `callout`, `report`) plus `citations` (article ids). Inline `[ARTICLE-ID]` tokens render as clickable citation chips. The full wire contract — blocks, the SSE event stream, and conversations — is specified in [`docs/api-contract.md`](docs/api-contract.md).

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

## Development

### Tests

```bash
# backend (JUnit 5; pure unit tests, no DB or API key needed)
docker run --rm -v "$PWD/backend:/app" -w /app maven:3.9-eclipse-temurin-21 mvn -B -ntp verify

# frontend (vitest)
cd frontend && npm install && npm test
```

CI runs both plus a production frontend build on every push / PR
(`.github/workflows/ci.yml`).

### Database migrations

`supabase/migrations/` is the source of truth (Hibernate `ddl-auto: none`).
After pulling changes with new migrations, run `supabase db reset` (or
`supabase migration up`) to apply them.

## Notes & next steps

- **Auth** — Supabase (gotrue) sessions end-to-end: the frontend signs in with
  Supabase, the backend validates the ES256 access tokens against Supabase's
  JWKS endpoint and enforces the `authenticated` audience claim (rejecting
  anon/service keys). Set `SUPABASE_JWT_ISSUER` to also pin the issuer.
- **Chat** — streaming (SSE) with per-user conversation memory: threads are
  persisted, the recent transcript is replayed into each prompt, and answers
  can be rated (👍/👎) via the feedback endpoint.
- **Retrieval** — hybrid: pgvector similarity fused with Postgres full-text
  (reciprocal-rank fusion), so exact tokens like `CVE-2024-21413` match
  reliably. Questions containing IOCs (IP/domain/hash/email) are additionally
  grounded via the KB's indicator index (`articles.iocs`).
- **Connectors** — NVD is live: manual sync from the UI plus an optional
  nightly schedule (`NVD_SYNC_ENABLED=true`). Set `NVD_API_KEY` to lift NVD's
  aggressive anonymous rate limits.
- **Ingestion** — uploads are extension-allowlisted and size-capped; URL
  imports are fetched for real behind an SSRF guard (only public http(s)
  hosts, redirects re-validated, body capped at 2 MB). Jobs are per-user:
  everyone sees the seeded demo jobs, but only your own jobs can be
  retried/deleted.
- Hibernate `ddl-auto` is `none` (the migrations are the source of truth).
