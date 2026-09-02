// views/Alerts.jsx — watchlist management + matched-article alert feed.

import { useState } from 'react';
import { Icon } from '../icons.jsx';
import { DimensionDivider, RedlineNote, SeverityTag, StampButton } from '../ui.jsx';
import {
  useWatchlists, useAlerts, useCreateWatchlist, useDeleteWatchlist,
  useMarkAlertRead, useMarkAllAlertsRead,
} from '../lib/queries.js';

const SEVERITIES = ['critical', 'high', 'medium', 'low', 'info'];

export default function Alerts({ onOpenArticle }) {
  const watchlistsQuery = useWatchlists();
  const alertsQuery = useAlerts();
  const unreadQuery = useAlerts({ unread: true });
  const createWatchlist = useCreateWatchlist();
  const deleteWatchlist = useDeleteWatchlist();
  const markRead = useMarkAlertRead();
  const markAllRead = useMarkAllAlertsRead();

  const [label, setLabel] = useState('');
  const [terms, setTerms] = useState('');
  const [minSeverity, setMinSeverity] = useState('');
  const [formError, setFormError] = useState('');

  const watchlists = watchlistsQuery.data || [];
  const alerts = alertsQuery.data || [];
  const unread = unreadQuery.data || [];

  function handleCreate(e) {
    e.preventDefault();
    setFormError('');
    const termList = terms.split(',').map((t) => t.trim()).filter(Boolean);
    if (!label.trim()) { setFormError('Label is required.'); return; }
    if (termList.length === 0) { setFormError('At least one watch term is required (comma-separated).'); return; }
    createWatchlist.mutate(
      { label: label.trim(), terms: termList, minSeverity: minSeverity || null },
      {
        onSuccess: () => { setLabel(''); setTerms(''); setMinSeverity(''); },
        onError: (err) => setFormError(String(err?.message || err)),
      }
    );
  }

  return (
    <div className="alerts">
      <div className="view-head">
        <div>
          <h1 className="view-title">Watchlists &amp; Alerts</h1>
          <p className="view-sub">Subscribe to CVEs, products and threat actors · alerts fire on connector syncs</p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <RedlineNote>// KEV · NVD · ATT&amp;CK feeds watched</RedlineNote>
          <div className="assistant-scope" role="status" aria-live="polite">
            <Icon name="bell" size={14} />
            <span>{unread.length} UNREAD · {watchlists.length} WATCHLISTS</span>
          </div>
        </div>
      </div>

      <div className="alerts-body">
        <div className="alerts-col">
          <DimensionDivider label="ALERT FEED" dim="AUTO" />
          <div className="alerts-toolbar">
            <span className="alerts-count">{alerts.length} matched articles</span>
            <StampButton
              action="MARK ALL"
              context="READ"
              disabled={unread.length === 0 || markAllRead.isPending}
              onClick={() => markAllRead.mutate()}
            >
              <Icon name="check" size={12} /> MARK ALL READ
            </StampButton>
          </div>
          <div className="alerts-feed" role="feed" aria-label="Watchlist alerts">
            {alertsQuery.isPending && <div className="alerts-empty" role="status">Loading alerts…</div>}
            {alertsQuery.isError && (
              <div className="alerts-empty" role="alert">
                Alerts unavailable — {String(alertsQuery.error?.message || alertsQuery.error)}
              </div>
            )}
            {alertsQuery.isSuccess && alerts.length === 0 && (
              <div className="alerts-empty">
                No alerts yet. Create a watchlist — when a connector sync lands a matching
                article (CVE id, product, actor name), it appears here.
              </div>
            )}
            {alerts.map((a) => (
              <article key={a.id} className={'alert-item' + (a.read ? '' : ' unread')}>
                <button
                  type="button"
                  className="alert-open"
                  onClick={() => { if (!a.read) markRead.mutate(a.id); onOpenArticle && onOpenArticle(a.articleId); }}
                  title={a.articleTitle}
                >
                  <span className="alert-dot" aria-hidden="true" />
                  <span className="alert-meta">
                    <span className="alert-title">{a.articleTitle || a.articleId}</span>
                    <span className="alert-sub">
                      <span className="mono">{a.articleId}</span>
                      {a.reason && <span className="alert-reason">{a.reason}</span>}
                    </span>
                  </span>
                  <SeverityTag level={a.severity || 'info'} small />
                  {!a.read && <span className="alert-badge">NEW</span>}
                </button>
              </article>
            ))}
          </div>
        </div>

        <aside className="watch-side" aria-label="Watchlists">
          <div className="watch-label">YOUR WATCHLISTS</div>
          <div className="watch-list">
            {watchlistsQuery.isPending && <div className="alerts-empty" role="status">Loading…</div>}
            {watchlistsQuery.isSuccess && watchlists.length === 0 && (
              <div className="alerts-empty">No watchlists yet — add one below.</div>
            )}
            {watchlists.map((w) => (
              <div key={w.id} className="watch-item">
                <div className="watch-item-head">
                  <Icon name="radar" size={13} />
                  <span className="watch-item-label">{w.label}</span>
                  <button
                    type="button"
                    className="conv-del"
                    title="Delete watchlist"
                    aria-label={`Delete watchlist: ${w.label}`}
                    disabled={deleteWatchlist.isPending && deleteWatchlist.variables === w.id}
                    onClick={() => {
                      if (window.confirm(`Delete watchlist "${w.label}" and its alerts?`)) {
                        deleteWatchlist.mutate(w.id);
                      }
                    }}
                  >
                    <Icon name="trash" size={12} />
                  </button>
                </div>
                <div className="watch-terms">
                  {w.minSeverity && <span className="watch-sev">≥ {w.minSeverity}</span>}
                  {(w.terms || []).map((t) => <span key={t} className="kw-tag">{t}</span>)}
                </div>
              </div>
            ))}
          </div>

          <form className="watch-form" onSubmit={handleCreate}>
            <div className="watch-label">NEW WATCHLIST</div>
            <input
              className="watch-input"
              placeholder="Label — e.g. Edge device CVEs"
              value={label}
              maxLength={60}
              onChange={(e) => setLabel(e.target.value)}
              aria-label="Watchlist label"
            />
            <input
              className="watch-input"
              placeholder="Terms, comma-separated — CVE-2024-21413, sonicwall, akira"
              value={terms}
              maxLength={300}
              onChange={(e) => setTerms(e.target.value)}
              aria-label="Watch terms"
            />
            <select
              className="watch-select"
              value={minSeverity}
              onChange={(e) => setMinSeverity(e.target.value)}
              aria-label="Minimum severity"
            >
              <option value="">Any severity</option>
              {SEVERITIES.map((s) => <option key={s} value={s}>≥ {s}</option>)}
            </select>
            {formError && <div className="watch-error" role="alert">{formError}</div>}
            <StampButton
              type="submit"
              action="CREATE"
              context="WATCH"
              disabled={createWatchlist.isPending}
            >
              <Icon name={createWatchlist.isPending ? 'refresh' : 'radar'} size={13} />
              {createWatchlist.isPending ? 'CREATING…' : 'CREATE WATCHLIST'}
            </StampButton>
          </form>
        </aside>
      </div>
    </div>
  );
}
