// views/CommandPalette.jsx — Ctrl+K command palette: navigation, settings,
// theme, starter prompts. Pure keyboard-first, ESC to close.

import { useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '../icons.jsx';

export default function CommandPalette({
  open, onClose, onNavigate, onOpenSettings, onAsk, onToggleTheme, theme,
}) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef(null);
  const listRef = useRef(null);

  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      requestAnimationFrame(() => inputRef.current && inputRef.current.focus());
    }
  }, [open]);

  const commands = useMemo(() => {
    const nav = [
      { id: 'nav-assistant', icon: 'chat', label: 'Go to Assistant', hint: 'NAV', run: () => onNavigate('/') },
      { id: 'nav-kb', icon: 'book', label: 'Go to Knowledge Base', hint: 'NAV', run: () => onNavigate('/kb') },
      { id: 'nav-ingest', icon: 'ingest', label: 'Go to Ingestion', hint: 'NAV', run: () => onNavigate('/ingest') },
      { id: 'nav-alerts', icon: 'bell', label: 'Go to Watchlists & Alerts', hint: 'NAV', run: () => onNavigate('/alerts') },
      { id: 'settings', icon: 'settings', label: 'Open settings', hint: 'CFG', run: onOpenSettings },
      {
        id: 'theme', icon: theme === 'dark' ? 'sun' : 'moon',
        label: `Theme: ${theme} — cycle dark / light / system`, hint: 'UI',
        run: onToggleTheme,
      },
    ];
    const asks = (onAsk ? [
      { id: 'ask-cve', icon: 'search', label: 'Ask: latest critical CVEs in the KB', hint: 'ASK', run: () => onAsk('What are the latest critical CVEs in the knowledge base?') },
      { id: 'ask-bec', icon: 'doc', label: 'Ask: business email compromise playbook', hint: 'ASK', run: () => onAsk('What is the playbook for responding to a business email compromise?') },
      { id: 'ask-rotation', icon: 'key', label: 'Ask: emergency patch deployment', hint: 'ASK', run: () => onAsk('How do we run an emergency out-of-band patch deployment?') },
    ] : []);
    return [...nav, ...asks];
  }, [onNavigate, onOpenSettings, onAsk, onToggleTheme, theme]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return commands;
    return commands.filter((c) => c.label.toLowerCase().includes(q));
  }, [commands, query]);

  useEffect(() => { setActive(0); }, [query]);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); }
      else if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((a) => Math.min(a + 1, filtered.length - 1));
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((a) => Math.max(a - 1, 0));
      } else if (e.key === 'Enter' && filtered[active]) {
        e.preventDefault();
        filtered[active].run();
        onClose();
      }
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, filtered, active, onClose]);

  useEffect(() => {
    const el = listRef.current && listRef.current.children[active];
    if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
  }, [active]);

  if (!open) return null;

  return (
    <div className="palette-backdrop" onClick={onClose}>
      <div
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="palette-input-row">
          <Icon name="command" size={15} />
          <input
            ref={inputRef}
            className="palette-input"
            placeholder="Type a command or question…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            aria-label="Command search"
          />
          <span className="palette-kbd">ESC</span>
        </div>
        <div className="palette-list" ref={listRef} role="listbox" aria-label="Commands">
          {filtered.length === 0 && <div className="palette-empty">No matching commands</div>}
          {filtered.map((c, i) => (
            <button
              key={c.id}
              type="button"
              role="option"
              aria-selected={i === active}
              className={'palette-item' + (i === active ? ' active' : '')}
              onMouseEnter={() => setActive(i)}
              onClick={() => { c.run(); onClose(); }}
            >
              <Icon name={c.icon} size={14} />
              <span className="palette-item-label">{c.label}</span>
              <span className="palette-item-hint">{c.hint}</span>
            </button>
          ))}
        </div>
        <div className="palette-foot">
          <span>↑↓ navigate</span>
          <span>↵ run</span>
          <span>SENTINEL // CMD</span>
        </div>
      </div>
    </div>
  );
}
