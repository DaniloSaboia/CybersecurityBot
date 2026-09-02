// App.jsx — Red Noir shell: auth gate, routed views, theme, command palette.

import { useState, useEffect, useCallback } from 'react';
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { Icon } from './icons.jsx';
import { Logo, CursorCoordinateHUD } from './ui.jsx';
import { useMe, queryClient } from './lib/queries.js';
import { useAuth } from './lib/auth.jsx';
import { ErrorBoundary } from './ErrorBoundary.jsx';
import { getStoredTheme, cycleTheme, applyTheme, watchSystemPreference, THEMES } from './lib/theme.js';
import Login from './views/Login.jsx';
import Assistant from './views/Assistant.jsx';
import KnowledgeBase from './views/KnowledgeBase.jsx';
import Ingest from './views/Ingest.jsx';
import Alerts from './views/Alerts.jsx';
import CommandPalette from './views/CommandPalette.jsx';
import SettingsModal, { getStoredModel, DEFAULT_MODELS } from './views/SettingsModal.jsx';

const NAV = [
  { id: 'assistant', path: '/', label: 'Assistant', icon: 'chat', sheet: 'RAG · CHAT', exact: true },
  { id: 'kb', path: '/kb', label: 'Knowledge Base', icon: 'book', sheet: 'ARCHIVE' },
  { id: 'ingest', path: '/ingest', label: 'Ingestion', icon: 'ingest', sheet: 'PIPELINE' },
  { id: 'alerts', path: '/alerts', label: 'Watchlists', icon: 'bell', sheet: 'FEED · AUTO' },
];

export default function App() {
  const { user, loading, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [openId, setOpenId] = useState(null);
  const [pendingAsk, setPendingAsk] = useState(null);
  const [navOpen, setNavOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [activeModel, setActiveModel] = useState(getStoredModel);
  const [theme, setTheme] = useState(getStoredTheme);
  const [railCollapsed, setRailCollapsed] = useState(
    () => localStorage.getItem('rail-collapsed') === '1'
  );
  const { data: me } = useMe();

  // Theme: apply on mount + whenever it changes; follow the OS in 'system'.
  useEffect(() => { applyTheme(theme); }, [theme]);
  useEffect(() => watchSystemPreference(theme, setTheme), [theme]);

  // Refetch everything with the new token whenever the signed-in user changes.
  useEffect(() => { queryClient.invalidateQueries(); }, [user?.id]);

  // Global command palette shortcut (Ctrl/Cmd+K).
  useEffect(() => {
    function onKey(e) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  // Cross-view bridges arrive via router location.state.
  useEffect(() => {
    const state = location.state || {};
    if (state.openId) setOpenId(state.openId);
    if (state.ask) setPendingAsk({ text: state.ask, n: Date.now() });
    if (state.palette) setPaletteOpen(true);
  }, [location.state]);

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
  const openArticle = useCallback((id) => {
    setOpenId(id);
    navigate('/kb', { state: { openId: id } });
    setNavOpen(false);
  }, [navigate]);

  // cross-view bridge: KB "Ask assistant" -> switch to chat and send
  const askAssistant = useCallback((text) => {
    setPendingAsk({ text, n: Date.now() });
    navigate('/', { state: { ask: text } });
    setNavOpen(false);
  }, [navigate]);

  const handleToggleTheme = useCallback(() => setTheme(cycleTheme(getStoredTheme())), []);

  async function handleSignOut() {
    await signOut();
    queryClient.clear();
  }

  if (loading) {
    return (
      <div style={{ height: '100vh', display: 'grid', placeItems: 'center', fontFamily: 'var(--mono)', color: 'var(--accent-bright)', fontSize: 13, background: 'var(--bg)' }}>
        <div style={{ border: '1px solid var(--accent-dim)', borderRadius: 999, padding: '16px 28px', background: 'var(--accent-soft)', boxShadow: 'var(--accent-glow)' }}>
          [ INITIALIZING SENTINEL CONSOLE… ]
        </div>
      </div>
    );
  }
  if (!user) return <Login />;

  const envName = (me && me.env) || 'PROD · us-east';
  const userName = (me && me.user) || user.email || 'analyst';
  const userRole = (me && me.role) || 'Security Analyst';
  const activeNav = NAV.find((n) => (n.exact
    ? location.pathname === n.path
    : location.pathname.startsWith(n.path))) || NAV[0];

  const modelObj = DEFAULT_MODELS.find((m) => m.id === activeModel);
  const modelShortName = modelObj ? modelObj.name.replace(' Instruct', '') : activeModel.split('/')[1] || activeModel;
  const unreadAlerts = 0; // badge count refined by the Alerts view's polling

  return (
    <div className={'app' + (railCollapsed ? ' rail-collapsed' : '')}>
      <aside className={'rail' + (navOpen ? ' open' : '')}>
        <Logo />
        <nav className="rail-nav">
          {NAV.map((n) => (
            <button
              key={n.id}
              className={'rail-item' + (activeNav.id === n.id ? ' active' : '')}
              onClick={() => { navigate(n.path); setNavOpen(false); }}
            >
              <Icon name={n.icon} size={17} />
              <span>{n.label}</span>
              {n.id === 'alerts' && unreadAlerts > 0 && <span className="rail-badge">{unreadAlerts}</span>}
              {activeNav.id === n.id && <span className="rail-active-bar" />}
            </button>
          ))}
          <button className="rail-item" onClick={() => setPaletteOpen(true)} title="Command palette (Ctrl+K)">
            <Icon name="command" size={17} />
            <span>Commands</span>
            <span className="rail-kbd">^K</span>
          </button>
          <button className="rail-item settings-rail-item" onClick={() => { setSettingsOpen(true); setNavOpen(false); }}>
            <Icon name="settings" size={17} />
            <span>Settings</span>
          </button>
        </nav>
        <div className="rail-foot">
          <div className="env-badge" onClick={() => setSettingsOpen(true)} style={{ cursor: 'pointer' }} title="Click to view environment &amp; model configuration">
            <span className="status-dot" style={{ background: 'var(--accent)' }} />
            <div>
              <div className="env-name">{envName}</div>
              <div className="env-sub">{modelShortName}</div>
            </div>
          </div>
          <div className="rail-user">
            <div className="avatar avatar-user sm"><Icon name="user" size={13} /></div>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="user-name" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{userName}</div>
              <div className="user-role">{userRole}</div>
            </div>
            <button className="topbar-icon" title="Sign out" style={{ width: 28, height: 28 }} onClick={handleSignOut}>
              <Icon name="arrowleft" size={13} />
            </button>
          </div>
        </div>
      </aside>

      {navOpen && <div className="rail-scrim" onClick={() => setNavOpen(false)} />}

      <main className="main">
        <div className="topbar">
          <button className="topbar-menu" title="Toggle sidebar" onClick={handleMenuToggle}><Icon name="menu" size={17} /></button>
          <div className="topbar-crumb">
            <span className="crumb-root">SENTINEL // KMS</span>
            <Icon name="chevron" size={12} />
            <span>{activeNav.label}</span>
            <span style={{ color: 'var(--text-faint)', fontSize: 10 }}>[{activeNav.sheet}]</span>
          </div>

          <div className="topbar-right">
            {/* Quick Model Selector Chip */}
            <button
              className="topbar-model-chip"
              onClick={() => setSettingsOpen(true)}
              title="Click to change AI Model / OpenRouter Settings"
            >
              <Icon name="spark" size={12} />
              <span className="topbar-model-label">MODEL:</span>
              <span className="topbar-model-val">{modelShortName}</span>
              <span className="topbar-model-badge">FREE</span>
            </button>

            <CursorCoordinateHUD />

            <button
              className="topbar-icon"
              title="Command palette (Ctrl+K)"
              onClick={() => setPaletteOpen(true)}
            >
              <Icon name="command" size={15} />
            </button>

            <button
              className="topbar-icon"
              title={`Theme: ${theme} (cycles ${THEMES.join(' / ')})`}
              aria-label={`Theme: ${theme}. Activate to cycle theme.`}
              onClick={handleToggleTheme}
            >
              <Icon name={theme === 'light' ? 'sun' : 'moon'} size={15} />
            </button>

            <button
              className="topbar-icon"
              title="System &amp; AI Settings"
              onClick={() => setSettingsOpen(true)}
            >
              <Icon name="settings" size={15} />
            </button>

            <button className="topbar-icon" title="Refresh data" onClick={() => queryClient.invalidateQueries()}>
              <Icon name="refresh" size={15} />
            </button>
          </div>
        </div>

        <div className="view-wrap">
          <ErrorBoundary key={activeNav.id}>
            <Routes location={location}>
              <Route
                path="/"
                element={(
                  <Assistant
                    onOpenArticle={openArticle}
                    pendingAsk={pendingAsk}
                    activeModel={activeModel}
                    onOpenSettings={() => setSettingsOpen(true)}
                  />
                )}
              />
              <Route
                path="/kb"
                element={<KnowledgeBase openId={openId} setOpenId={setOpenId} onAsk={askAssistant} />}
              />
              <Route path="/kb/*" element={<KnowledgeBase openId={openId} setOpenId={setOpenId} onAsk={askAssistant} />} />
              <Route path="/ingest" element={<Ingest />} />
              <Route path="/alerts" element={<Alerts onOpenArticle={openArticle} />} />
              <Route path="*" element={<Assistant onOpenArticle={openArticle} activeModel={activeModel} onOpenSettings={() => setSettingsOpen(true)} />} />
            </Routes>
          </ErrorBoundary>
        </div>

        {/* Command Palette */}
        <CommandPalette
          open={paletteOpen}
          onClose={() => setPaletteOpen(false)}
          onNavigate={(path) => navigate(path)}
          onOpenSettings={() => setSettingsOpen(true)}
          onAsk={askAssistant}
          onToggleTheme={handleToggleTheme}
          theme={theme}
        />

        {/* Global Settings Modal */}
        <SettingsModal
          isOpen={settingsOpen}
          onClose={() => setSettingsOpen(false)}
          onSave={({ model }) => {
            setActiveModel(model);
            queryClient.invalidateQueries({ queryKey: ['settings'] });
          }}
        />
      </main>
    </div>
  );
}
