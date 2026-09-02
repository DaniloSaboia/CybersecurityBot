# Sentinel KMS — SecOps Knowledge Management System

A cybersecurity knowledge console with three surfaces:

1. **Assistant** — a RAG chatbot that answers security questions grounded in the knowledge base, with inline citations, multi-turn conversation memory (standalone-question rewriting), IOC-aware lookups, optional LLM re-ranking, and answer feedback.
2. **Knowledge Base** — a searchable library of security documents (threat intel, policies, postmortems, playbooks, CVE advisories, compliance frameworks).
3. **Ingestion** — a document pipeline (Ingest → Extract → Chunk → Embed → Index) with source connectors (NVD, CISA KEV, MITRE ATT&CK) and live job status into a vector store.
4. **Watchlists** — CVE/product/actor subscriptions that fire alerts when connector syncs land matching articles.

**Stack:** Spring Boot 3.4 (Java 17) · React 18 + Vite · hosted Supabase Postgres + **pgvector** · [Spring AI](https://docs.spring.io/spring-ai/reference/) with **OpenRouter chat**, Mistral embeddings, or local Ollama.

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
                 │        provider = OpenRouter │ Mistral │ Ollama     │
                 └───────────────┬───────────────────┬────────────────┘
                                 │ JDBC              │ embeddings/chat
                 ┌───────────────▼─────────┐   ┌─────▼──────────────┐
                 │ hosted Supabase Postgres │   │ OpenRouter/Mistral │
                 │  + pgvector              │   │ or Ollama (:11434) │
                 │  + Supabase Auth         │   └────────────────────┘
                 └──────────────────────────┘
```

Embeddings are **1024-dim** so the same `vector(1024)` schema works for both `mistral-embed` (Mistral) and `mxbai-embed-large` (Ollama). Switching providers with a different dimension means editing the migration and re-embedding.

---

## Prerequisites

- **Java 17** and **Maven 3.9+** for the backend.
- **Node.js 20+** and npm for the frontend.
- A hosted **Supabase** project with the schema migration applied.
- An **OpenRouter API key** for free-router chat and a **Mistral API key** for 1024-dimension embeddings.
- (Optional) Ollama for an entirely local AI profile.

---

## Quick start

### 1. Configure hosted Supabase

Create a Supabase project, apply `supabase/migrations/20260829000000_init_schema.sql`, and fill in the database, project URL, anon key, JWKS URL, and JWT issuer values. Use the session-pooler connection details for environments where direct Postgres connections are unavailable. See [`docs/supabase-hosted-setup.md`](docs/supabase-hosted-setup.md) for the complete setup.

### 2. Configure environment

```bash
cp .env.example .env
```

The default `.env.example` uses the `openrouter` profile. Add:

- `OPENROUTER_API_KEY` for chat through the current free-model router.
- `MISTRAL_API_KEY` for ingestion and query embeddings.

The backend and Vite automatically load the repository-root `.env`; only `VITE_`-prefixed variables are exposed to browser code.

### 3. Run the app

Locally:

```bash
# terminal 1
cd backend
mvn spring-boot:run

# terminal 2
cd frontend
npm ci
npm run dev
```

Or as a one-command Docker stack (pgvector DB + backend + frontend, optional
Ollama profile for fully local AI):

```bash
docker compose up                      # db + backend + frontend
docker compose --profile local-ai up   # + Ollama
```

For local Ollama without Docker, set `SPRING_PROFILES_ACTIVE=local`, pull `llama3.1` and `mxbai-embed-large`, then start both applications with Ollama listening on `localhost:11434`.

### 4. Open it

- App: **http://localhost:5173**
- API docs (Swagger): **http://localhost:8080/swagger-ui.html**
- Supabase Studio: open it from your hosted project dashboard

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
| POST | `/api/connectors/kev/sync?max=` | pull CISA known-exploited vulnerabilities |
| POST | `/api/connectors/attack/sync?max=` | pull MITRE ATT&CK Enterprise techniques |
| GET | `/api/watchlists` | own watchlists (with unread alert counts) |
| POST | `/api/watchlists` | create a watchlist (`{label, terms[], minSeverity}`) |
| DELETE | `/api/watchlists/{id}` | delete a watchlist (cascades its alerts) |
| GET | `/api/alerts?unread=` | own alert feed (newest first) |
| POST | `/api/alerts/{id}/read` | mark one alert read |
| POST | `/api/alerts/read-all` | mark every alert read |
| GET | `/api/eval/cases` | retrieval-eval golden set |
| POST | `/api/eval/retrieval` | run the golden set, report recall@k / MRR / nDCG |
| GET | `/api/vectorstore/status` | engine, model, chunk count, dim, health |

The chat response is an ordered array of typed **blocks** (`text`, `steps`, `callout`, `report`) plus `citations` (article ids). Inline `[ARTICLE-ID]` tokens render as clickable citation chips. The full wire contract — blocks, the SSE event stream, and conversations — is specified in [`docs/api-contract.md`](docs/api-contract.md).

---

## Project layout

```
CybersecurityBot/
├─ .env.example               provider + hosted DB + RAG config
├─ docs/                      hosted setup + API contract
├─ supabase/
│  └─ migrations/             schema, RLS, pgvector, indexes
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

- `application-openrouter.yml` → OpenRouter chat + Mistral embeddings
- `application-mistral.yml` → Mistral chat + embeddings
- `application-local.yml` → Ollama chat + embeddings

To add another OpenAI-compatible provider, add the matching `spring-ai-starter-model-*` dependency and a profile. If its embedding dimension ≠ 1024, update `vector(1024)` in the migration, `spring.ai.vectorstore.pgvector.dimensions`, and re-embed.

## Development

### Tests

```bash
# backend (JUnit 5; pure unit tests, no DB or API key needed)
cd backend
mvn spring-boot:run &   # not required for tests — see below
mvn -B -ntp verify
# HybridSearchIntegrationTest self-skips when Docker is unavailable and runs
# the real hybrid-SQL against a pgvector container when it is.

# frontend (vitest)
cd ../frontend
npm ci
npm test -- --run

# end-to-end smoke (Playwright; builds the app and serves it via vite preview)
npm run test:e2e
```

CI runs backend + frontend tests plus a production frontend build on every
push / PR, a Playwright E2E job, and CodeQL security scanning
(`.github/workflows/`). Dependabot keeps dependencies current; CD builds and
publishes the Docker images to GHCR on merges to `main` (`cd.yml`). A nightly
workflow runs the RAG retrieval eval (`eval.yml`).

### Database migrations

`supabase/migrations/` is the source of truth (Hibernate `ddl-auto: none`).
Apply new migrations through the Supabase SQL editor or your linked Supabase CLI project.

## Notes & next steps

- **Auth** — Supabase (gotrue) sessions end-to-end: the frontend signs in with
  Supabase, the backend validates the ES256 access tokens against Supabase's
  JWKS endpoint, pins `SUPABASE_JWT_ISSUER`, and enforces the `authenticated`
  audience claim (rejecting anon/service keys).
- **Chat** — streaming (SSE) with per-user conversation memory: threads are
  persisted, the recent transcript is replayed into each prompt, and answers
  can be rated (👍/👎) via the feedback endpoint. Multi-turn follow-ups are
  rewritten into standalone retrieval questions first (`RAG_QUERY_REWRITE`),
  and an optional LLM re-ranker re-orders the fused candidate window
  (`RAG_RERANK=true`, off by default — it adds one model call per turn).
  Retrieval can be scoped per-request via `category` / `minSeverity`.
- **Retrieval** — hybrid: pgvector similarity fused with Postgres full-text
  (reciprocal-rank fusion), so exact tokens like `CVE-2024-21413` match
  reliably. Questions containing IOCs (IP/domain/hash/email) are additionally
  grounded via the KB's indicator index (`articles.iocs`).
- **Eval harness** — a golden set (`backend/src/main/resources/eval/`) runs
  through the live retrieval path and reports recall@k, MRR and nDCG via
  `POST /api/eval/retrieval`; a nightly CI workflow re-runs it.
- **Connectors** — NVD, CISA KEV and MITRE ATT&CK are live: manual sync from
  the UI plus an optional nightly schedule for NVD (`NVD_SYNC_ENABLED=true`).
  Set `NVD_API_KEY` to lift NVD's aggressive anonymous rate limits.
- **Watchlists** — users subscribe to CVE ids, products or threat-actor names
  (with an optional minimum severity); every connector sync pushes new
  articles past the matcher and files alerts into the per-user feed.
- **Hardening** — per-user rate limits on the chat + ingest surfaces
  (Bucket4j, `RATELIMIT_*`), an SSRF-guarded URL fetcher, extension-allowlisted
  uploads, and prompt-injection defenses: ingested documents are sanitized
  (`ContentSanitizer`) and every prompt frames retrieved sources as untrusted
  data, never instructions.
- **Observability** — Prometheus metrics at `/actuator/prometheus`
  (authenticated by default; `METRICS_OPEN=true` opens it on trusted
  networks), chat latency / citation / model-failure / connector metrics,
  and structured JSON logs (`LOG_JSON=true`, on in the Docker image).
- **Ingestion** — uploads are extension-allowlisted and size-capped; URL
  imports are fetched for real behind an SSRF guard (only public http(s)
  hosts, redirects re-validated, body capped at 2 MB). Jobs are per-user:
  everyone sees the seeded demo jobs, but only your own jobs can be
  retried/deleted.
- Hibernate `ddl-auto` is `none` (the migrations are the source of truth).
