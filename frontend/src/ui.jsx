// ui.jsx — shared presentational primitives for the KMS shell.

import { useState } from 'react';
import { Icon } from './icons.jsx';

// severity color/background tokens (CSS variables from index.css)
const SEV_META = {
  critical: { c: 'var(--sev-critical)', bg: 'var(--sev-critical-bg)' },
  high: { c: 'var(--sev-high)', bg: 'var(--sev-high-bg)' },
  medium: { c: 'var(--sev-medium)', bg: 'var(--sev-medium-bg)' },
  low: { c: 'var(--sev-low)', bg: 'var(--sev-low-bg)' },
  info: { c: 'var(--sev-info)', bg: 'var(--sev-info-bg)' },
};

// severity labels + rank derived locally (rank 4->0 drives severity sort)
export const SEVERITY = {
  critical: { label: 'CRITICAL', rank: 4 },
  high: { label: 'HIGH', rank: 3 },
  medium: { label: 'MEDIUM', rank: 2 },
  low: { label: 'LOW', rank: 1 },
  info: { label: 'INFO', rank: 0 },
};

export function SeverityTag({ level, small }) {
  const m = SEV_META[level] || SEV_META.info;
  const label = (SEVERITY[level] || { label: level }).label;
  return (
    <span className="sev-tag" style={{
      color: m.c, background: m.bg, borderColor: m.c,
      fontSize: small ? 9.5 : 10.5, padding: small ? '1px 5px' : '2px 7px',
    }}>
      <span className="sev-dot" style={{ background: m.c }} />
      {label}
    </span>
  );
}

// CatTag accepts either a category object (`cat`) or an `id` + `index` Map to look it up
export function CatTag({ id, cat, index }) {
  const c = cat || (index && (index.get ? index.get(id) : index[id])) || null;
  if (!c) return null;
  return <span className="cat-tag">{c.abbr}<span className="cat-tag-full">· {c.label}</span></span>;
}

export function Tag({ children }) {
  return <span className="kw-tag">{children}</span>;
}

// status dot for connectors / jobs
export function StatusDot({ status }) {
  const map = {
    connected: 'var(--accent)', indexed: 'var(--accent)', connected2: 'var(--accent)',
    syncing: 'var(--sev-medium)', embedding: 'var(--sev-medium)', chunking: 'var(--sev-medium)',
    extracting: 'var(--sev-medium)', queued: 'var(--text-faint)',
    error: 'var(--sev-critical)', failed: 'var(--sev-critical)',
  };
  const c = map[status] || 'var(--text-faint)';
  const pulse = ['syncing', 'embedding', 'chunking', 'extracting'].includes(status);
  return <span className={'status-dot' + (pulse ? ' pulse' : '')} style={{ background: c, boxShadow: `0 0 0 3px color-mix(in srgb, ${c} 16%, transparent)` }} />;
}

export function ConnectorIcon({ kind }) {
  const map = { S3: 's3', API: 'api', URL: 'url' };
  return <Icon name={map[kind] || 'database'} size={16} />;
}

export function IocTypeIcon({ type }) {
  const map = { domain: 'globe', ip: 'globe', sha256: 'hash', email: 'user', md5: 'hash' };
  return <Icon name={map[type] || 'hash'} size={13} />;
}

// terminal-style window chrome dots
export function TermDots() {
  return (
    <div className="term-dots">
      <span /><span /><span />
    </div>
  );
}

export function Logo({ compact }) {
  return (
    <div className="logo">
      <div className="logo-mark"><Icon name="shield" size={20} /></div>
      {!compact && (
        <div className="logo-text">
          <span className="logo-name">SENTINEL</span>
          <span className="logo-sub">KMS // SEC-OPS</span>
        </div>
      )}
    </div>
  );
}

export function Pill({ children, tone = 'default', onClick, active }) {
  return (
    <button className={`pill pill-${tone}${active ? ' active' : ''}`} onClick={onClick}>
      {children}
    </button>
  );
}

// "cited source" chip used in chat + article relations.
// Resolves the article title via a passed-in index (Map id->article); falls
// back to rendering the bare id when the article is not (yet) loaded.
export function SourceChip({ id, onOpen, index }) {
  const a = index && (index.get ? index.get(id) : index[id]);
  return (
    <button className="source-chip" onClick={() => onOpen && onOpen(id)}>
      <span className="source-chip-id">{a ? a.id : id}</span>
      {a && <span className="source-chip-title">{a.title}</span>}
      <Icon name="chevron" size={13} className="source-chip-arrow" />
    </button>
  );
}

export function fmtDate(s) {
  try {
    return new Date(s).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  } catch (e) { return s; }
}

// copy-to-clipboard with transient confirmation (icon swaps to check for 1.3s)
export function CopyBtn({ text, className = '', label, size = 12 }) {
  const [done, setDone] = useState(false);
  function copy() {
    try { navigator.clipboard && navigator.clipboard.writeText(text); } catch (e) {}
    setDone(true);
    clearTimeout(copy._t);
    copy._t = setTimeout(() => setDone(false), 1300);
  }
  return (
    <button className={className + (done ? ' copied' : '')} onClick={copy} title="Copy">
      <Icon name={done ? 'check' : 'copy'} size={size} />
      {label != null && <span>{done ? 'Copied' : label}</span>}
    </button>
  );
}
