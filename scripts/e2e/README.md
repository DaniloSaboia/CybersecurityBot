# Disposable real-stack browser runner

Run from the repository root with **Node 24.20.0** on PATH:

```sh
node --test scripts/e2e/runner-safety.test.mjs
SENTINEL_REAL_STACK_E2E=1 node scripts/e2e/disposable-real-stack.mjs
```

Requires Maven, Java 17, Docker CLI, Chromium for the installed Playwright version,
and the isolated Colima profile `sentinel-kms-test`. The runner deliberately pins
`DOCKER_HOST=unix:///Users/danilopalaciosaboia/.colima/sentinel-kms-test/docker.sock`
and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` on every child.
It refuses other Node versions. Java defaults to Homebrew openjdk@17; an explicitly
provided `JAVA_HOME` is permitted. No existing `.env` or credential files are read.

## What runs

- An allowlisted source snapshot under a new `/tmp/sentinel-real-stack-*` directory;
  production backend sources plus only `SentinelDisposableBrowserHarness` on the
  test classpath. Other concurrently edited unit tests are not compiled or run.
- Fresh PostgreSQL/pgvector (`pgvector/pgvector:pg16`) and GoTrue (`supabase/gotrue:v2.177.0`)
  containers, with a uniquely labeled owned network. Ports bind only to loopback.
- All snapshot SQL migrations, in filename order, unchanged. A migration SHA-256
  manifest is retained beside redacted `execution.log`.
- Actual GoTrue signup and password-token exchange for two random temporary users.
  ES256 keys are generated in memory; GoTrue publishes its real JWKS. A transparent
  HTTP prefix gateway adapts `/auth/v1/*` to GoTrue's root paths; it never mints tokens
  or returns substitute auth responses.
- The real backend `SecurityConfig`, issuer/audience/signature verification, data
  services, durable ingestion worker, PostgreSQL, hybrid retrieval, persistence,
  SSE, and exact-evidence endpoint.
- The real frontend browser login and `frontend/e2e/real-stack.spec.js`, without
  `page.route` or other HTTP interception. The runner invokes from snapshot/frontend:

  ```sh
  CI=1 SENTINEL_REAL_STACK_E2E=1 npm run test:e2e:real -- --config ../scripts/e2e/disposable.playwright.config.mjs
  ```

  The extra config selects an ephemeral loopback UI port instead of touching an
  existing preview on 5173. It inherits the frontend real-suite configuration,
  forces no retries, trace/screenshot/video off, and no HTML report. The repository
  frontend files are neither edited nor replaced.

## Explicit provider substitution (not model-quality evaluation)

Only chat and embedding **providers** are deterministic test implementations. They
make no remote AI requests or model downloads. The vector writer and pgvector SQL
remain real. The chat provider refuses to answer unless the production retrieval
prompt contains both the uploaded `cobalt lantern` marker and the separately
registered governed article passage plus an exact server-issued evidence token.
This verifies integration/provenance, not live-model relevance or generation quality.
The article fixture is registered through `TrustedArticleRegistrationService` and
indexed through `EmbeddingIndexService`; a private upload is not falsely represented
as a governed article citation.

## Safety / artifacts / cleanup

Only allowlisted process configuration (PATH, HOME, TMPDIR, JAVA_HOME) is inherited;
no ambient provider/auth/database credentials are forwarded. Temporary auth and DB
credentials are generated in memory and injected into children, not command-line
values or `.env` files. The four browser `E2E_*` credential variables are used only
by the existing auth helper. Logs redact exact generated secrets and JWT strings.
Docker logging is disabled; its client uses a new empty config directory instead of
personal registry credentials. Maven uses the checked-in empty test settings for
both user/global settings; npm does not load personal user settings. GoTrue startup
output is held in memory and only a redacted failure is reported. No browser storageState, traces, screenshots, or video
are requested. Transient Playwright result directories are removed on cleanup.

SIGINT/SIGTERM and normal/failure exits stop only owned child process groups and
containers whose unique owner label matches. No `docker prune`, global process
kills, existing container reuse, or production DB/provider calls. A private bridge
is used, not Docker's `--internal` network: on this daemon internal networks suppress
published loopback ports. Network isolation is not claimed as an egress firewall;
provider disabling and local-only configuration prevent external provider use.

Optional failed-job retry and alert generation require extra fixtures and are not
currently supplied; the existing suite explicitly skips retry and still tests
watchlist persistence. This is not a hosted deployment or a full backend regression
suite. Do not interpret startup or JWT checks alone as browser-suite success; only
`REAL STACK BROWSER RUN PASSED` after Playwright exits successfully means that run
completed.

## GoTrue reference evidence

Configuration follows the pinned release's official implementation:

- https://github.com/supabase/auth/blob/v2.177.0/internal/conf/jwk.go — `GOTRUE_JWT_KEYS`
  decodes a JSON array of private JWKs; exactly one has `key_ops: ["sign", "verify"]`.
- https://github.com/supabase/auth/blob/v2.177.0/internal/api/jwks.go — asymmetric
  public JWKS, `kid`, and ES256 signing algorithm selection.
- https://github.com/supabase/auth/blob/v2.177.0/internal/api/api.go — the default
  CORS header list does not include Supabase JS's `apikey`; the harness explicitly
  adds it via `GOTRUE_CORS_ALLOWED_HEADERS` so real browser password login works.
- https://github.com/supabase/auth/blob/v2.177.0/migrations/20221003041349_add_mfa_schema.up.sql
  — unqualified enum creation requires the connection `search_path=auth`, otherwise
  later migrations referencing `auth.factor_type` fail on a plain pgvector image.

The production JWT decoder is not replaced or weakened.
