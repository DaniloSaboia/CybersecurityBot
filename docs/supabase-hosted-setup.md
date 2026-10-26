Sentinel KMS — Hosted Supabase setup and remaining actions

Already completed and verified

Project: baljaxakiwhdyojtmnrc
Database host: db.baljaxakiwhdyojtmnrc.supabase.co:5432
Database: postgres

All 10 repository migrations were validated in a transaction that was rolled back, then committed together to the previously empty public schema. A second connection verified all 10 migration versions and SQL hashes in supabase_migrations.schema_migrations.

pgvector 0.8.2 is installed in extensions. The vector_store embedding column is vector(1024); HNSW, full-text and metadata indexes exist. All 28 application tables have RLS enabled. anon/authenticated have no direct table SELECT grants: the browser must use the authenticated Spring API for application data.

Local configuration is in the repository-root .env, ignored by Git and mode 600. The database password is not included in this document. Do not commit or paste that file.

Running now

Frontend: http://localhost:5187
Backend health: http://127.0.0.1:8080/actuator/health
Protected API: http://127.0.0.1:8080/api/me (401 without a user token is expected)

Backend health returned UP. Hosted PostgreSQL confirms 10 live JDBC connections, all using TLS. Frontend HTTP returned 200. Port 5173 was already occupied; its existing process was left untouched. No demo articles, jobs, or vectors were inserted.

Required actions in the Supabase dashboard

1. Browser publishable key configured and verified (2026-09-06).
   The existing publishable key for this project is now set in VITE_SUPABASE_ANON_KEY in the ignored repository-root .env. Despite its legacy variable name, it contains a browser-safe sb_publishable_ key, not a service_role or secret key.
   VITE_SUPABASE_URL is https://baljaxakiwhdyojtmnrc.supabase.co.
   The running frontend at http://localhost:5187 was checked and serves the configured project URL and key. Supabase /auth/v1/settings returned HTTP 200 with that key.

2. Auth configuration verified; create your application account.
   Email sign-in and signup are enabled; email confirmation is required.
   Site URL was changed from http://localhost:3000 to http://localhost:5187 so confirmation returns to the running app. No wildcard redirect URLs were added.
   Create/sign up your own user and complete email confirmation, then sign in. For production email delivery configure an appropriate SMTP provider; do not disable confirmation merely to bypass an error.
   Use an application-user password, NOT the PostgreSQL database password. No default admin password is configured by this setup.
   The hosted ES256 JWKS endpoint is reachable and configured in Spring; no signing-key rotation is needed for compatibility.

3. Provision an administrator deliberately, if you need connector/lifecycle/audit administration.
   No administrator was guessed or seeded. After creating your user, copy its UUID from Authentication > Users. A trusted administrator can use SQL Editor with the following template, replacing only YOUR_AUTH_USER_UUID with that exact UUID:

   INSERT INTO public.workspace_memberships (id, workspace_id, subject, role, active)
   VALUES (gen_random_uuid()::text, 'shared', 'YOUR_AUTH_USER_UUID', 'admin', true)
   ON CONFLICT (workspace_id, subject)
   DO UPDATE SET role = EXCLUDED.role, active = true;

   This grants administration in the shared workspace only. Evaluation execution requires a separate explicit operational grant; a workspace admin does not automatically receive it. Do not grant database client roles direct table access or BYPASSRLS.

4. Security follow-up.
   Rotate the database password because it was shared in conversation, then update only local DB_PASSWORD and restart the backend. Enable SSL enforcement in Supabase if not already enabled. The current connection uses sslmode=require (encrypted TLS); stronger server certificate verification requires the dashboard CA certificate and a verify-full configuration.
   Do not rerun migrations manually in SQL Editor: their application and history are already verified. Future deployments must apply only new migrations, not silently rewrite deployed versions.

AI provider configuration (updated 2026-09-06)

The ignored local .env now selects OpenRouter free-router chat and nvidia/llama-nemotron-embed-vl-1b-v2:free embeddings, using the user's server-side OpenRouter key. The embedding endpoint returned 1024-dimensional vectors at reported cost 0. The hosted vector index was empty before switching. See docs/openrouter-free-models.md for the current alternatives and reindexing constraints.

Validation boundary

Hosted: migrations, pgvector schema/indexes/RLS, live TLS JDBC connection, backend health and frontend serving verified. Hosted Auth accepts the configured public key and the running frontend serves it. Successful user login, upload/indexing and generated answers have NOT been verified; an application account is still required to validate the complete hosted browser workflow. Provider connectivity is documented separately in docs/openrouter-free-models.md.

Earlier disposable real-stack testing used real Auth/JWT/Postgres/pgvector but deterministic test AI: 2 browser tests passed, optional failed-job retry skipped. That is not hosted/live-model validation. Expert annotation, analyst evaluation, optional retry/alert browser coverage and test scheduler teardown cleanup remain separate open work.

Logs
   /tmp/sentinel-hosted-backend.log
   /tmp/sentinel-hosted-frontend.log
Migration receipt
   /tmp/sentinel-hosted-migration-receipt.json
Frozen migration manifest
   /tmp/sentinel-hosted-migration-input/manifest.json
