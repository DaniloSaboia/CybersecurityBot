// App.jsx — shell: nav rail, top bar, view routing, cross-view bridges.

import { useState } from 'react';
import { Icon } from './icons.jsx';
import { Logo } from './ui.jsx';
import { useMe, queryClient } from './lib/queries.js';
import Assistant from './views/Assistant.jsx';
import KnowledgeBase from './views/KnowledgeBase.jsx';
import Ingest from './views/Ingest.jsx';

const NAV = [
  { id: 'assistant', label: 'Assistant', icon: 'chat' },
  { id: 'kb', label: 'Knowledge Base', icon: 'book' },
  { id: 'ingest', label: 'Ingestion', icon: 'ingest' },
];

export default function App() {
  const [view, setView] = useState('assistant');
  const [openId, setOpenId] = useState(null);
  const [pendingAsk, setPendingAsk] = useState(null);
  const [navOpen, setNavOpen] = useState(false);

  const { data: me } = useMe();

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

  const envName = (me && me.env) || 'PROD · us-east';
  const envSub = (me && me.tenant) || 'SOC tenant';
  const userName = (me && me.user) || 'r.castellano';
  const userRole = (me && me.role) || 'Tier-3 Analyst';

  return (
    <div className="app">
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
            <div><div className="user-name">{userName}</div><div className="user-role">{userRole}</div></div>
          </div>
        </div>
      </aside>

      {navOpen && <div className="rail-scrim" onClick={() => setNavOpen(false)} />}

      <main className="main">
        <div className="topbar">
          <button className="topbar-menu" onClick={() => setNavOpen(true)}><Icon name="menu" size={18} /></button>
          <div className="topbar-crumb"><span className="crumb-root">Sentinel</span><Icon name="chevron" size={13} /><span>{NAV.find((n) => n.id === view).label}</span></div>
          <div className="topbar-right">
            <div className="topbar-search"><Icon name="search" size={14} /><input placeholder="Quick find…" /><kbd>⌘K</kbd></div>
            <button className="topbar-icon" title="Refresh" onClick={() => queryClient.invalidateQueries()}><Icon name="refresh" size={16} /></button>
          </div>
        </div>

        <div className="view-wrap">
          {view === 'assistant' && <Assistant onOpenArticle={openArticle} pendingAsk={pendingAsk} />}
          {view === 'kb' && <KnowledgeBase openId={openId} setOpenId={setOpenId} onAsk={askAssistant} />}
          {view === 'ingest' && <Ingest />}
        </div>
      </main>
    </div>
  );
}
