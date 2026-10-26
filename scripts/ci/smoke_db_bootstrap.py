"""Exercise fresh Docker DB initialization using real repository migrations.

Requires a running Docker daemon. Uses an isolated, disposable container only;
never reads .env or connects to a configured application database.
"""
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]


def docker(*args, check=True):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check)


def main():
    name = "sentinel-bootstrap-" + uuid.uuid4().hex[:12]
    try:
        docker("run", "--name", name, "-d", "-e", "POSTGRES_PASSWORD=local-bootstrap-test",
               "-v", f"{ROOT / 'docker/db-init'}:/docker-entrypoint-initdb.d:ro",
               "-v", f"{ROOT / 'supabase/migrations'}:/supabase-migrations:ro",
               "pgvector/pgvector:pg16")
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            state = docker("inspect", name, "--format", "{{.State.Status}}", check=False)
            if state.stdout.strip() == "exited":
                raise RuntimeError("Database initialization failed:\n" + docker("logs", name).stdout)
            # TCP avoids accepting the temporary initialization server on its Unix socket.
            ready = docker("exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", check=False)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Database bootstrap timed out")
        result = docker("exec", name, "psql", "-U", "postgres", "-d", "postgres",
                        "-v", "ON_ERROR_STOP=1", "-Atc",
                        "select count(*) from information_schema.tables where table_schema='public' "
                        "and table_name in ('articles','vector_store','ingest_jobs','watchlists','kb_alerts')")
        if result.stdout.strip() != "5":
            raise RuntimeError("Required application tables were not initialized")
        print("Fresh pgvector bootstrap passed: real migration scripts applied and core tables exist")
    finally:
        docker("rm", "-fv", name, check=False)


if __name__ == "__main__":
    main()
