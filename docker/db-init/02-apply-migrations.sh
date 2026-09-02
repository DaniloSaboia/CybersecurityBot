#!/bin/bash
# docker/db-init/02-apply-migrations.sh — applies the supabase migrations to
# the fresh pgvector container (runs inside docker-entrypoint-initdb.d).
set -e
echo "applying supabase migrations..."
for f in /supabase-migrations/*.sql; do
  echo "  -> $f"
  psql -v ON_ERROR_STOP=1 -U postgres -d postgres -f "$f"
done
echo "migrations applied."
