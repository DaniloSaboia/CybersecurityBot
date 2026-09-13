// Application shell: authenticated workspace, navigation, and global commands.

import { useState, useEffect, useCallback, useRef } from 'react';
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom';
import { Icon } from './icons.jsx';
import { Logo } from './ui.jsx';
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
  { id: 'assistant', path: '/', label: 'Assistant', icon: 'chat', exact: true },
  { id: 'kb', path: '/kb', label: 'Knowledge Base', icon: 'book' },
  { id: 'ingest', path: '/ingest', label: 'Ingestion', icon: 'ingest' },
  { id: 'alerts', path: '/alerts', label: 'Watchlists', icon: 'bell' },
];

function getOwnedRouterAsk(state, userId) {
  if (typeof state?.ask !== 'string' || !state.ask.trim() || state.askOwnerId !== userId) return null;
  return state.ask;
}

export default function App() {
  const { user, loading, sessionExpired, signOut } = useAuth();

  if (loading) {
    return (
      <div style={{ height: '100dvh', display: 'grid', placeItems: 'center', fontFamily: 'var(--sans)', color: 'var(--text-2)', fontSize: 13, background: 'var(--bg)' }}>
        <div role="status">
          Loading Sentinel…
        </div>
      </div>
    );
  }
  if (!user) return <Login sessionExpired={sessionExpired} />;

  // A keyed boundary is intentional: it removes all protected component-local
  // state (including conversations and pending view work) before a successor
  // user's queries have a chance to resolve.
  return <AuthenticatedConsole key={user.id} user={user} signOut={signOut} />;
}

function AuthenticatedConsole({ user, signOut }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [openId, setOpenId] = useState(null);
  const [pendingAsk, setPendingAsk] = useState(null);
  const consumedTransientLocationKey = useRef(null);
  const [navOpen, setNavOpen] = useState(false);
  const [mobileNav, setMobileNav] = useState(() => window.matchMedia?.('(max-width: 860px)').matches || false);
  const railRef = useRef(null);
  const menuRef = useRef(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [activeModel, setActiveModel] = useState(() => getStoredModel(user.id));
  const [theme, setTheme] = useState(getStoredTheme);
  const [railCollapsed, setRailCollapsed] = useState(
    () => localStorage.getItem('rail-collapsed') === '1'
  );
  const { data: me } = useMe();

  // Theme: apply on mount + whenever it changes; follow the OS in 'system'.
  useEffect(() => { applyTheme(theme); }, [theme]);
  useEffect(() => watchSystemPreference(theme, setTheme), [theme]);

  useEffect(() => {
    const media = window.matchMedia?.('(max-width: 860px)');
    if (!media) return;
    function onLayoutChange(event) {
      setMobileNav(event.matches);
      if (!event.matches) setNavOpen(false);
    }
    media.addEventListener('change', onLayoutChange);
    return () => media.removeEventListener('change', onLayoutChange);
  }, []);

  useEffect(() => {
    if (!navOpen) return;
    const rail = railRef.current;
    const controls = () => [...rail.querySelectorAll('button:not(:disabled), a[href]')]
      .filter((element) => element.getClientRects().length);
    // Wait until the browser has applied drawer visibility and main's inert state.
    const focusFrame = window.requestAnimationFrame(() => controls()[0]?.focus());
    function onDrawerKey(event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        setNavOpen(false);
      }
      if (event.key === 'Tab') {
        const items = controls();
        const first = items[0];
        const last = items.at(-1);
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    }
    document.addEventListener('keydown', onDrawerKey);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener('keydown', onDrawerKey);
      menuRef.current?.focus();
    };
  }, [navOpen]);

  // Global command palette shortcut (Ctrl/Cmd+K).
  useEffect(() => {
    function onKey(e) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setNavOpen(false);
        setPaletteOpen((o) => !o);
      }
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  // Cross-view bridges arrive via router location.state.
  useEffect(() => {
    const state = location.state && typeof location.state === 'object' ? location.state : {};
    const hasTransientState = Object.keys(state).length > 0;
    if (!hasTransientState) {
      consumedTransientLocationKey.current = null;
      return;
    }
    // React StrictMode replays effects in development. Consume each router
    // history entry once so a valid ask cannot be enqueued twice.
    if (consumedTransientLocationKey.current === location.key) return;
    consumedTransientLocationKey.current = location.key;
    if (state.openId) setOpenId(state.openId);
    if (state.ask !== undefined) {
      const ask = getOwnedRouterAsk(state, user.id);
      setPendingAsk(ask ? { text: ask, n: Date.now() } : null);
    }
    if (state.palette) setPaletteOpen(true);
    // Route state is a transient cross-view bridge, not a transcript store.
    // Consume it after both accepted and rejected asks so prompts and owner
    // identifiers cannot survive an account switch in browser history.
    navigate({ pathname: location.pathname, search: location.search, hash: location.hash }, {
      replace: true,
      state: null,
    });
  }, [location, navigate, user.id]);

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
    navigate('/', { state: { ask: text, askOwnerId: user.id } });
    setNavOpen(false);
  }, [navigate, user.id]);

  const handleToggleTheme = useCallback(() => setTheme(cycleTheme(getStoredTheme())), []);

  async function handleSignOut() {
    await signOut();
  }

  const envName = (me && me.env) || 'Security workspace';
  const userName = (me && me.user) || user.email || 'analyst';
  const userRole = (me && me.role) || 'Security Analyst';
  const activeNav = NAV.find((n) => (n.exact
    ? location.pathname === n.path
    : location.pathname.startsWith(n.path))) || NAV[0];

  const modelObj = DEFAULT_MODELS.find((m) => m.id === activeModel);
  const modelShortName = modelObj ? modelObj.name.replace(' Instruct', '') : activeModel.split('/')[1] || activeModel;
  const navExpanded = mobileNav ? navOpen : !railCollapsed;
  return (
    <div className={'app' + (railCollapsed ? ' rail-collapsed' : '')}>
      <a className="skip-link" href="#workspace-content">Skip to workspace</a>
      <aside id="workspace-navigation" ref={railRef} className={'rail' + (navOpen ? ' open' : '')} aria-label="Workspace navigation">
        <div className="rail-brand"><Logo /><button type="button" className="rail-close topbar-icon" aria-label="Close navigation" onClick={() => setNavOpen(false)}><Icon name="x" size={17} /></button></div>
        <button type="button" className="rail-search" aria-label="Search workspace commands" title="Search workspace commands" onClick={() => { setNavOpen(false); setPaletteOpen(true); }}>
          <Icon name="search" size={16} /><span>Search commands</span><kbd>⌘ K</kbd>
        </button>
        <div className="rail-section-label">Workspace</div>
        <nav className="rail-nav" aria-label="Primary navigation">
          {NAV.map((n) => (
            <button
              key={n.id}
              className={'rail-item' + (activeNav.id === n.id ? ' active' : '')}
              aria-label={n.label}
              aria-current={activeNav.id === n.id ? 'page' : undefined}
              title={n.label}
              onClick={() => { navigate(n.path); setNavOpen(false); }}
            >
              <Icon name={n.icon} size={17} />
              <span>{n.label}</span>
              {activeNav.id === n.id && <span className="rail-active-bar" />}
            </button>
          ))}
        </nav>
        <div className="rail-foot">
          <div className="env-badge">
            <Icon name="database" size={16} />
            <div>
              <div className="env-name">{envName}</div>
              <div className="env-sub" title={`Active model: ${modelShortName}`}>{modelShortName}</div>
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

      {navOpen && <button type="button" className="rail-scrim" tabIndex={-1} aria-label="Dismiss navigation" onClick={() => setNavOpen(false)} />}

      <main className="main" inert={navOpen ? '' : undefined}>
        <div className="topbar">
          <button ref={menuRef} className="topbar-menu" title="Toggle sidebar" aria-label="Toggle sidebar" aria-controls="workspace-navigation" aria-expanded={navExpanded} onClick={handleMenuToggle}><Icon name="menu" size={17} /></button>
          <div className="topbar-crumb">
            <span className="crumb-root">Workspace</span>
            <Icon name="chevron" size={12} />
            <span>{activeNav.label}</span>

          </div>

          <div className="topbar-right">
            <button
              className="topbar-icon"
              title="Command palette (Ctrl+K)"
              aria-label="Commands"
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
              title="Settings — models and retrieval"
              aria-label="Settings"
              onClick={() => setSettingsOpen(true)}
            >
              <Icon name="settings" size={15} />
            </button>

            <button className="topbar-icon" title="Refresh data" onClick={() => queryClient.invalidateQueries()}>
              <Icon name="refresh" size={15} />
            </button>
          </div>
        </div>

        <div id="workspace-content" className="view-wrap" tabIndex={-1}>
          <ErrorBoundary key={activeNav.id}>
            <Routes location={location}>
              <Route
                path="/"
                element={(
                  <Assistant
                  onOpenArticle={openArticle}
                  pendingAsk={pendingAsk}
                  activeModel={activeModel}
                  userId={user.id}
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
              <Route path="*" element={<Assistant onOpenArticle={openArticle} activeModel={activeModel} userId={user.id} onOpenSettings={() => setSettingsOpen(true)} />} />
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
          userId={user.id}
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
