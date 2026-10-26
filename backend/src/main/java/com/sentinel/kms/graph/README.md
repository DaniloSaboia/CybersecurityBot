# Governed graph contract

This is a minimal, article-only assertion store. Each entity and each edge names one exact immutable evidence passage. `sourced` records an author's assertion of source support; `inferred` records an inference with a required method and method version. Neither label constitutes a new approval decision or a claim about retrieval quality.

## HTTP API

All routes require the authenticated JWT subject. Roles, workspace, classification, creator and graph IDs are derived by the server.

- `POST /api/graph/entities` → 201: `{entityType, label, provenance}`.
- `POST /api/graph/edges` → 201: `{fromEntityId, toEntityId, relationType, provenance}`.
- `GET /api/graph/entities/{id}` and `GET /api/graph/edges/{id}` → 200 or an indistinguishable 404 for missing, private, revoked, stale or unavailable evidence.
- Missing subject → 401; invalid input → 400; denied mutation → 403; storage failure → redacted 503.

`provenance` input is `{evidence, statementKind, inferenceMethod, inferenceVersion, observedAt}`. `statementKind` is exactly `sourced` or `inferred`. Sourced rows require null inference fields. Inferred rows require nonblank method and version. `observedAt` is an ISO timestamp supplied by the author, distinct from server `recordedAt`.

`evidence` is the shared `EvidenceReference`: articleId, articleRevisionId, revisionNumber, evidenceSnapshotId, sourceSpanStart, sourceSpanEnd, sourceUri, sourceVersion, revisionStatus. Spans are nonempty, zero-based and half-open, bounded by PostgreSQL `char_length(content)` (Unicode characters). The API requires exact URI/version values, including nulls where canonical data is null. It rejects resolver output that changes any core ID, revision number, span, URI or version; the returned revisionStatus must be approved. No article-ID-only, upload, vector-metadata or historical fallback exists.

Outputs retain the canonical reference, DB-derived workspace/classification/evidence SHA-256, statement kind/method/version, author observation time, server record time/creator, and immutable recordVersion=1. There are no update/delete endpoints, graph traversal, automatic extraction, embeddings, caches, provider calls or frontend integration in this package.

## Authorization and storage

- Call the shared current evidence resolver on writes and reads; it invokes DB-backed `KnowledgeAccessService.canReadArticle`. Reads fetch only the article authorization key before that shared gate, then re-resolve the exact evidence before returning content.
- Writes additionally require a current workspace editor role. They lock governance rows in article-ID order, then current workflow rows, matching lifecycle lock order, and re-resolve evidence after those locks. They do not mutate shared lifecycle, evidence, revision or membership rows.
- Edges require readable current evidence for both endpoints as well as their own passage. All three rows must have the same workspace and classification; this deliberately restricts cross-scope edges.
- Migration `20260905060000_knowledge_graph.sql` creates only graph tables, indexes, triggers and a graph binding function. Immutable composite revision FKs, exact snapshot/revision/number/span/URI/version/hash checks, and current ag/ws approval, active lifecycle and clock-time expiry checks protect writes. `article_revisions.status` is never used as current approval.
- Graph rows remain immutable after evidence revocation or publication changes, but disappear from this API. Old rows are never retargeted to a replacement revision. An edge is hidden when any of its three evidence bindings becomes unavailable.
- RLS is enabled and all direct PUBLIC/anon/authenticated privileges are revoked. There is no client SQL policy or competing graph-local subject policy. The trusted Spring backend performs subject authorization through the shared Java boundary. Database-owner access remains privileged.
- Use `KnowledgeAuditService.record(KnowledgeAuditEvent.of(...))` with `AuditActions.GRAPH_ENTITY_CREATE`, `GRAPH_EDGE_CREATE`, `GRAPH_ENTITY_READ`, `GRAPH_EDGE_READ`; types `graph-entity`/`graph-edge`, typed SUCCESS/DENIED/FAILURE and empty metadata. Only server-generated/proven target IDs and proven FK fields enter events. Audit persistence is best effort, with no `recordRequired` dependency or audit-delivery guarantee.

Authorization reflects the live facts at the checks; it is not a permanent grant. No authorization cache or claim of revocation synchronization beyond the shared policy boundary is introduced.

## Verification and handoff

Build in an isolated copy to avoid shared target-directory races. From the copied backend:

```sh
DOCKER_HOST=unix:///Users/danilopalaciosaboia/.colima/sentinel-kms-test/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
mvn -o -Dtest=GraphServiceTest,KnowledgeGraphPostgresIntegrationTest test
```

The integration suite starts and removes a real pgvector PostgreSQL 16 test container, applies the repository init plus all migrations in order, and makes no external model/provider calls. It does not skip when Docker is missing. HTTP tests run Spring MockMvc with a synthetic JWT principal resolver and real PostgreSQL/service authorization; they are not a live browser or JWT-signature test.

2026-09-06 handoff: 16 tests, 0 failures/errors/skips (11 PostgreSQL integration cases + 5 unit cases). Coverage includes storage/API roundtrip, sourced/inferred provenance, membership revocation, public reader mutation denial, restricted clearance, workflow revocation, endpoint revocation, expiry without a lifecycle job, exact tuple forgery, direct-client SQL denial, immutable history, current revision replacement, and a two-connection publication/revocation race.

Exact local logs:

- `/tmp/cairn-graph/logs/02-schema-red.log`: expected missing-storage assertion, 1 failure.
- `/tmp/cairn-graph/logs/03-service-red.log`: expected missing GraphService compilation failure.
- `/tmp/cairn-graph/logs/06-endpoint-red.log`: expected revoked-endpoint insertion regression, 1 failure.
- `/tmp/cairn-graph/logs/09-refreshed-contracts-green.log`: 16 passed against refreshed peer contracts and all migrations.

Only the graph package, its tests and migration60000 belong to this handoff. Shared resolver/lifecycle40000, audit50000, eval70000 and frontend remain with their owners. No old migration, shared config/CI, todo, production data or Git commit is changed by this work.
