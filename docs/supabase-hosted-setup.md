# Connecting Sentinel KMS to Hosted Supabase

This guide walks you through migrating from a local Supabase CLI instance to a hosted **Supabase Cloud** project.

---

## 1. Create a Supabase Project

1. Log in to [Supabase Dashboard](https://app.supabase.com).
2. Click **New project**, choose an organization, give it a name (e.g. `sentinel-kms`), and set a strong database password.
3. Select your preferred region and click **Create new project**.

---

## 2. Apply Database Schema & Migrations

1. Go to **SQL Editor** in your Supabase dashboard sidebar.
2. Click **New Query**.
3. Open [`supabase/migrations/20260829000000_init_schema.sql`](../supabase/migrations/20260829000000_init_schema.sql) from this repository, copy its entire contents, and paste it into the SQL Editor.
4. Click **Run**.
5. Verify that the tables (`articles`, `categories`, `connectors`, `ingest_jobs`, `conversations`, `messages`, `vector_store`) and indexes were created successfully.

---

## 3. Retrieve Credentials from Supabase

Go to **Project Settings** in your Supabase dashboard:

1. **API Settings** (`Project Settings` > `API`):
   - **Project URL**: `https://<your-project-ref>.supabase.co`
   - **Project API Keys (anon public)**: `eyJhbGciOi...`
2. **Database Connect dialog**:
   - Choose the **Session pooler** for this long-running Spring/Hikari service.
   - **Host**: copy the displayed regional pooler host.
   - **Port**: `5432`
   - **User**: `postgres.<your-project-ref>`
   - **Password**: The password you set in Step 1.
3. **Auth Settings** (`Project Settings` > `API`):
   - **JWKS Endpoint**: `https://<your-project-ref>.supabase.co/auth/v1/.well-known/jwks.json`
   - **Issuer**: `https://<your-project-ref>.supabase.co/auth/v1`

---

## 4. Configure `.env`

Copy `.env.example` to a repository-level `.env`, then fill in the hosted
credentials. Spring and Vite are configured to load this file automatically;
only names beginning with `VITE_` are exposed to browser code.

```bash
# Frontend Supabase Client
VITE_SUPABASE_URL=https://<your-project-ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<your-anon-key>

# Backend JDBC Connection
DB_URL=jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=verify-full
DB_USERNAME=postgres.<your-project-ref>
DB_PASSWORD=<your-database-password>

# JWT Validation
SUPABASE_JWKS_URI=https://<your-project-ref>.supabase.co/auth/v1/.well-known/jwks.json
SUPABASE_JWT_ISSUER=https://<your-project-ref>.supabase.co/auth/v1
SUPABASE_JWT_AUDIENCE=authenticated

# AI providers (both are required by the default openrouter profile)
OPENROUTER_API_KEY=<your-openrouter-key>
MISTRAL_API_KEY=<your-mistral-key-for-1024-dim-embeddings>
```

`SUPABASE_JWT_ISSUER` is required for hosted deployments so the backend checks
both the token signature and the project that issued it. Never prefix database
or AI secrets with `VITE_`.

---

## 5. Launch the Application

1. **Backend**:
   ```bash
   cd backend
   mvn spring-boot:run
   ```
2. **Frontend**:
   ```bash
   cd frontend
   npm run dev
   ```

On first startup, the backend seeds the knowledge base and generates embeddings
in `vector_store`. Application tables have RLS enabled and direct `anon` /
`authenticated` Data API access revoked; data access goes through the
JWT-protected Spring API.
