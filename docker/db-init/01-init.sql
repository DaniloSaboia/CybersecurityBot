# docker/db-init/01-init.sql — prepares a plain pgvector container to mirror
# the hosted Supabase schema conventions used by the app:
#   * pgvector installed in the `extensions` schema
# The migrations themselves are applied by 02-apply-migrations.sh.

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA extensions;
