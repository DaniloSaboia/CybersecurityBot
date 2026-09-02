-- ============================================================================
-- Watchlists + Alerts
-- CVE/keyword watchlists per user; alerts fire when synced articles match.
-- ============================================================================

-- 1. Watchlists: user-scoped term lists with a minimum severity threshold.
CREATE TABLE IF NOT EXISTS watchlists (
    id TEXT PRIMARY KEY,
    owner_sub TEXT NOT NULL,
    label TEXT NOT NULL,
    terms TEXT[] NOT NULL DEFAULT '{}',
    min_severity TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_watchlists_owner ON watchlists(owner_sub);

-- 2. Alerts: one row per (watchlist, matching article) when a connector sync
--    lands something the user watches.
CREATE TABLE IF NOT EXISTS kb_alerts (
    id TEXT PRIMARY KEY,
    owner_sub TEXT NOT NULL,
    watchlist_id TEXT NOT NULL REFERENCES watchlists(id) ON DELETE CASCADE,
    article_id TEXT NOT NULL,
    article_title TEXT,
    severity TEXT,
    reason TEXT,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kb_alerts_owner ON kb_alerts(owner_sub, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kb_alerts_watchlist ON kb_alerts(watchlist_id);

-- 3. updated_at trigger for watchlists.
DROP TRIGGER IF EXISTS set_watchlists_updated_at ON watchlists;
CREATE TRIGGER set_watchlists_updated_at
    BEFORE UPDATE ON watchlists
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 4. Same RLS posture as every other table: the Spring API is the only client.
ALTER TABLE watchlists ENABLE ROW LEVEL SECURITY;
ALTER TABLE kb_alerts ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE watchlists, kb_alerts FROM anon, authenticated;
