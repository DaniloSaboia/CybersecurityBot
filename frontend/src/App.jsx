// App.jsx — shell: auth gate, nav rail, top bar, view routing, cross-view bridges.

import { useState, useEffect } from 'react';
import { Icon } from './icons.jsx';
import { Logo } from './ui.jsx';
import { useMe, queryClient } from './lib/queries.js';
import { useAuth } from './lib/auth.jsx';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import Login from './views/Login.jsx';
import Assistant from './views/Assistant.jsx';
import KnowledgeBase from './views/KnowledgeBase.jsx';
import Ingest from './views/Ingest.jsx';

const NAV = [
  { id: 'assistant', label: 'Assistant', icon: 'chat' },
  { id: 'kb', label: 'Knowledge Base', icon: 'book' },
  { id: 'ingest', label: 'Ingestion', icon: 'ingest' },
];

export default function App() {
  const { user, loading, signOut } = useAuth();
  const [view, setView] = useState('assistant');
  const [openId, setOpenId] = useState(null);
  const [pendingAsk, setPendingAsk] = useState(null);
  const [navOpen, setNavOpen] = useState(false);
  const [railCollapsed, setRailCollapsed] = useState(
    () => localStorage.getItem('rail-collapsed') === '1'
  );
  const { data: me } = useMe();

  // Refetch everything with the new token whenever the signed-in user changes.
  useEffect(() => { queryClient.invalidateQueries(); }, [user?.id]);

  // Hamburger: opens the drawer on narrow screens, collapses the rail on desktop.
  function handleMenuToggle() {
    if (window.matchMedia('(max-width: 860px)').matches) {
      setNavOpen(true);
      return;
    }
    const next = !railCollapsed;
    setRailCollapsed(next);
    localStorage.setItem('rail-collapsed', next ? '1' : '0');
  }

  // cross-view bridge: chat citation -> open the article in the KB
  function openArticle(id) {
    setOpenId(id);
    setView('kb');
    setNavOpen(false);
  }

  // cross-view bridge: KB "Ask assistant" -> switch to chat and send
  function askAssistant(text) {
    setPendingAsk({ text, n: Date.now() });
    setView('assistant');
    setNavOpen(false);
  }

  async function handleSignOut() {
    await signOut();
    queryClient.clear();
  }

  if (loading) {
    return (
      <div style={{ height: '100vh', display: 'grid', placeItems: 'center', fontFamily: 'var(--mono)', color: 'var(--text-3)', fontSize: 12 }}>
        Loading…
      </div>
    );
  }
  if (!user) return <Login />;

  const envName = (me && me.env) || 'PROD · us-east';
  const envSub = (me && me.tenant) || 'SOC tenant';
  const userName = (me && me.user) || user.email || 'analyst';
  const userRole = (me && me.role) || 'Tier-3 Analyst';

  return (
    <div className={'app' + (railCollapsed ? ' rail-collapsed' : '')}>
      <aside className={'rail' + (navOpen ? ' open' : '')}>
        <Logo />
        <nav className="rail-nav">
          {NAV.map((n) => (
            <button key={n.id} className={'rail-item' + (view === n.id ? ' active' : '')} onClick={() => { setView(n.id); setNavOpen(false); }}>
              <Icon name={n.icon} size={18} />
              <span>{n.label}</span>
              {view === n.id && <span className="rail-active-bar" />}
            </button>
          ))}
        </nav>
        <div className="rail-foot">
          <div className="env-badge">
            <span className="status-dot" style={{ background: 'var(--accent)' }} />
            <div><div className="env-name">{envName}</div><div className="env-sub">{envSub}</div></div>
          </div>
          <div className="rail-user">
            <div className="avatar avatar-user sm"><Icon name="user" size={14} /></div>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="user-name" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{userName}</div>
              <div className="user-role">{userRole}</div>
            </div>
            <button className="topbar-icon" title="Sign out" style={{ width: 30, height: 30 }} onClick={handleSignOut}>
              <Icon name="arrowleft" size={14} />
            </button>
          </div>
        </div>
      </aside>

      {navOpen && <div className="rail-scrim" onClick={() => setNavOpen(false)} />}

      <main className="main">
        <div className="topbar">
          <button className="topbar-menu" title="Toggle sidebar" onClick={handleMenuToggle}><Icon name="menu" size={18} /></button>
          <div className="topbar-crumb"><span className="crumb-root">Sentinel</span><Icon name="chevron" size={13} /><span>{NAV.find((n) => n.id === view).label}</span></div>
          <div className="topbar-right">
            <button className="topbar-icon" title="Refresh" onClick={() => queryClient.invalidateQueries()}><Icon name="refresh" size={16} /></button>
          </div>
        </div>

        <div className="view-wrap">
          <ErrorBoundary key={view}>
            {view === 'assistant' && <Assistant onOpenArticle={openArticle} pendingAsk={pendingAsk} />}
            {view === 'kb' && <KnowledgeBase openId={openId} setOpenId={setOpenId} onAsk={askAssistant} />}
            {view === 'ingest' && <Ingest />}
          </ErrorBoundary>
        </div>
      </main>
    </div>
  );
}
