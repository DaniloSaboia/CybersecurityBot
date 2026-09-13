# Daily source refresh

Sentinel supports a persisted refresh queue for NVD, CISA KEV and the SANS Internet Storm Center full-text diary feed. In Ingestion, each connector has **Refresh now**, a daily schedule indicator, last complete check, run results, recent run history and indexing counts. A completed fetch is distinct from searchable content.

## Configuration

```dotenv
FEED_SYNC_ENABLED=true
FEED_SYNC_CRON=0 0 3 * * *
FEED_SYNC_ZONE=Atlantic/Azores
FEED_SYNC_MAX_CHANGES=10
FEED_INDEX_POLL_MS=15000
NVD_SYNC_ENABLED=false
```

Restart the backend after changing these variables. Daily-enabled sources queue their first run on startup; subsequent automatic runs use the configured cron/time zone. Manual refresh and automatic refresh use the same queue. Manual refresh requires the existing trusted connector-mutation permission; enabling schedules does not grant users new permissions. The old NVD-only toggle now guards its scheduler correctly and should remain off when the combined schedule is enabled.

Each run accepts at most ten changed records per source by default. This bounds initial embedding demand; it is not a guarantee that provider rate limits will permit all work immediately. The UI reports **partial** and remaining records when more source records need checking. Refresh again to continue, or increase the server limit (maximum 100) for a suitable provider budget. A low limit can leave an ongoing backlog on high-volume sources; do not interpret a partial refresh as complete current coverage.

## Source behavior

- **NVD:** queries modification dates, not only publication dates, follows pagination and persists its offset/window. First refresh starts with the preceding two days; subsequent windows use the last completed checkpoint with a one-hour overlap. A missed interval longer than 119 days is processed in bounded windows. Record corrections and published references become new revisions.
- **CISA KEV:** checks the complete catalog, newest entries first, and publishes changed records. If the canonical CISA endpoint is unavailable, it tries CISA's official GitHub mirror. Required action, exploitation/ransomware status and CISA due dates are preserved as attributed source information.
- **SANS ISC:** uses the official full-text RSS feed, keeping the source URL, publication date and supplied report text. It does not fetch images or recursively crawl links. The feed is a rolling window; it cannot reconstruct entries that disappeared while Sentinel was offline. Report content remains source evidence, not an AI-verified incident response plan.

A source's stable identifier is namespaced: `feed:nvd:CVE-…` and `feed:kev:CVE-…` coexist and link to each other. Same-source repeats create no new revision or embedding work. Changes create immutable revisions and evidence, supersede the previous workflow projection, and enqueue the existing revision-fenced indexing worker. Manually governed or edited records are not overwritten automatically; they require review. Legacy articles are retained rather than silently rewritten.

## Reliability and visibility

Queue state, source checkpoint and the ten most recent prior run summaries use the existing internal `connectors.config` JSON column. No schema migration or public database grants are required. Immutable source revision history uses the existing governance tables and is not limited to ten revisions.

A database lease prevents concurrent runs for the same source. Expired work can resume after a restart. Source publication, counters and the NVD cursor commit together. Failed fetches/parsing/publication are shown as failures and do not advance a successful checkpoint. Repeated manual requests while a run is active reuse the existing run.

The OpenRouter profile processes up to four background revision-index jobs per 15-second poll, leaving headroom under the observed 20-request-per-minute free endpoint allowance. Provider throttling remains possible when chat/uploads share the key; failed index jobs retain their retry state. The indexing card distinguishes searchable records, records awaiting indexing and indexing failures. Existing watchlist matching runs for new or changed source records, with its existing alert deduplication. API keys and internal cursor/lease fields are excluded from connector responses.

## Validation

Tests cover source parsing, NVD modification queries and pagination, RSS external-entity rejection, authorization before enqueue, scheduling disabled/enabled, repeated refreshes, changed content, cross-source CVEs, immutable history, transaction rollback, budget continuation and stale-worker recovery. Live fetch checks also parsed NVD, the complete KEV catalog and the full-text SANS feed successfully.

This milestone does not fix the separate general Markdown-upload limits: extraction still caps text at 200,000 characters, and its character chunker is not Markdown-aware.
