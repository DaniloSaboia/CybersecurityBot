# OpenRouter free chat and ingestion embeddings

The `openrouter` profile uses one server-side `OPENROUTER_API_KEY` for chat and embeddings. Keep it in the ignored root `.env`; never set it in a `VITE_*` variable. Restart the backend after changing it.

```dotenv
SPRING_PROFILES_ACTIVE=openrouter
OPENROUTER_BASE_URL=https://openrouter.ai/api/v1
OPENROUTER_CHAT_MODEL=openrouter/free
AI_EMBEDDING_PROVIDER=openai
OPENROUTER_EMBED_MODEL=nvidia/llama-nemotron-embed-vl-1b-v2:free
```

The base URL includes `/v1`, so Spring AI appends `/chat/completions` and `/embeddings` without a second `/v1`.

## Live catalogs

Settings reads [chat models](https://openrouter.ai/api/v1/models) and [embedding models](https://openrouter.ai/api/v1/embeddings/models), with a ten-minute cache. It includes models accepting text, with the appropriate output modality and all listed prices equal to zero. Audio-generation endpoints are excluded from the chat picker. Failed refreshes retain the last successful list and retry after one minute. Free endpoints remain subject to provider availability, rate limits and data policies.

## Daily free-model limits

An HTTP 429 with `error.metadata.limit_source=openrouter_free_tier_daily` means the configured account has exhausted its daily free-model allowance. Changing free models or retrying cannot bypass that account-wide limit. Other 429 responses can represent a temporary provider or minute limit and must not be treated as daily exhaustion. See [OpenRouter's rate-limit reference](https://openrouter.ai/docs/api_reference/limits).

The assistant records confirmed daily exhaustion in a shared, in-memory `GenerationAvailability` cooldown. It suppresses chat generation and optional query rewriting/re-ranking while blocked, while keeping authorized knowledge-base guidance available. The explanation includes the provider's `X-RateLimit-Reset` time in UTC when valid; this is a provider-reported reset, not a guarantee of model availability. If no usable reset is supplied, the cooldown lasts five minutes. It expires automatically and is cleared on backend restart. No credentials, raw provider errors, paid models, or provider changes are exposed or introduced by this handling.

On 2026-09-29 the configured account returned a daily limit of 50 with zero remaining and a reset of `2026-09-30T00:00:00Z`. This explained repeated unavailable responses despite relevant knowledge-base results; it was not a knowledge-base permission failure.

## Embedding compatibility verified on 2026-09-06

| Free OpenRouter model | Observed result requesting 1024 dimensions | Usage |
| --- | --- | --- |
| `nvidia/llama-nemotron-embed-vl-1b-v2:free` | 1024-dimensional response, reported cost 0 | Active ingestion and retrieval model |
| `liquid/lfm-2.5-embedding-350m:free` | 1024-dimensional response, reported cost 0 | Alternative; 512-token context requires smaller inputs |
| `nvidia/nemotron-3-embed-1b:free` | HTTP 400: dimensions must be 2048 | Requires a different vector schema and full reindex |

Do not switch embedding models per request or mix vectors from different models. Even matching dimensions do not make vector spaces compatible. The shared model is pinned in the backend; the browser lists alternatives without silently changing the index. The existing hosted index contained zero vectors before the initial switch.

Ingestion extracts text locally before submitting it to the embedding endpoint. This setup does not enable OpenRouter file parsing or paid fallback models. Catalog membership is not a guarantee of future availability or pricing; verify again before a large ingest.

Mistral remains an optional alternative through `AI_EMBEDDING_PROVIDER=mistral` with `MISTRAL_API_KEY`. The `local` profile retains Ollama chat and embeddings.

## Validation

On 2026-09-06 the application catalog returned 19 free text-chat options and three free embedding options. Spring AI 1.0.0 completed a live `openrouter/free` chat request and a batch of two 1024-dimensional embeddings with the configured model. A synthetic vector was inserted into the hosted pgvector table, queried with cosine distance, and rolled back. The backend was restarted with the OpenRouter profile and health returned UP.

Targeted validation passed: 24 backend tests covering catalog filtering, provider reporting, configuration and RAG; two Settings frontend tests; production frontend build. This verifies provider connectivity and the vector write/query path, not a complete authenticated browser upload job.
