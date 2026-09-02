// ui.jsx — shared presentational primitives for the Red Noir Sentinel KMS shell.

import { useState, useEffect, useRef } from 'react';
import { Icon } from './icons.jsx';

const VISUALLY_HIDDEN = {
  position: 'absolute',
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
};

// severity color/background tokens (CSS variables from index.css)
const SEV_META = {
  critical: { c: 'var(--sev-critical)', bg: 'var(--sev-critical-bg)', code: 'CRIT-04' },
  high: { c: 'var(--sev-high)', bg: 'var(--sev-high-bg)', code: 'HIGH-03' },
  medium: { c: 'var(--sev-medium)', bg: 'var(--sev-medium-bg)', code: 'MED-02' },
  low: { c: 'var(--sev-low)', bg: 'var(--sev-low-bg)', code: 'LOW-01' },
  info: { c: 'var(--sev-info)', bg: 'var(--sev-info-bg)', code: 'INFO-00' },
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
    <span className="sev-tag bp-stamp-tag" style={{
      color: m.c, background: m.bg, borderColor: m.c,
      fontSize: small ? 9 : 10, padding: small ? '1px 5px' : '2px 7px',
    }}>
      <span className="sev-dot" style={{ background: m.c }} />
      <span className="sev-code">[{label}]</span>
    </span>
  );
}

// CatTag accepts either a category object (`cat`) or an `id` + `index` Map to look it up
export function CatTag({ id, cat, index }) {
  const c = cat || (index && (index.get ? index.get(id) : index[id])) || null;
  if (!c) return null;
  return (
    <span className="cat-tag">
      <span className="cat-tag-bracket">[</span>
      {c.abbr}
      <span className="cat-tag-full"> · {c.label}</span>
      <span className="cat-tag-bracket">]</span>
    </span>
  );
}

export function Tag({ children }) {
  return <span className="kw-tag"><span className="kw-prefix">#</span>{children}</span>;
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
  return (
    <span
      className={'status-dot' + (pulse ? ' pulse' : '')}
      style={{ background: c, boxShadow: `0 0 0 3px color-mix(in srgb, ${c} 20%, transparent)` }}
    />
  );
}

export function ConnectorIcon({ kind }) {
  const map = { S3: 's3', API: 'api', URL: 'url' };
  return <Icon name={map[kind] || 'database'} size={15} />;
}

export function IocTypeIcon({ type }) {
  const map = { domain: 'globe', ip: 'globe', sha256: 'hash', email: 'user', md5: 'hash' };
  return <Icon name={map[type] || 'hash'} size={13} />;
}

// Blueprint Corner Crosshair Marks (CAD drafting alignment markers)
export function BlueprintCorners({ coords }) {
  return (
    <div className="bp-corners" aria-hidden="true">
      <span className="bp-corner bp-crosshair tl" />
      <span className="bp-corner bp-crosshair tr" />
      <span className="bp-corner bp-crosshair bl" />
      <span className="bp-corner bp-crosshair br" />
      {coords && <span className="bp-coord-tag">{coords}</span>}
    </div>
  );
}

// Dimension Divider: Technical dimension arrow lines `<─── LABEL ───>`
export function DimensionDivider({
  label,
  style,
  dim = '100%',
  className = '',
  role = 'separator',
  'aria-label': ariaLabel,
  ...dividerProps
}) {
  const hasLabel = label !== null && label !== undefined && label !== '';
  const hasDim = dim !== null && dim !== undefined && dim !== '';
  const labelText = typeof label === 'string' || typeof label === 'number' ? String(label) : '';
  const dimText = typeof dim === 'string' || typeof dim === 'number' ? String(dim) : '';
  const accessibleLabel = ariaLabel || [labelText, dimText && `dimension ${dimText}`].filter(Boolean).join(', ') || 'Dimension divider';

  return (
    <div
      {...dividerProps}
      className={`dim-divider${className ? ` ${className}` : ''}`}
      style={style}
      role={role}
      aria-label={accessibleLabel}
      aria-orientation={role === 'separator' ? 'horizontal' : undefined}
    >
      <span className="dim-arrow-l" aria-hidden="true">|◄──</span>
      <span className="dim-label" aria-hidden="true">
        {hasLabel && <span className="dim-label-text">{label}</span>}
        {hasLabel && hasDim && <span className="dim-meta-sep"> // </span>}
        {hasDim && <span className="dim-value">{dim}</span>}
      </span>
      <span className="dim-arrow-r" aria-hidden="true">──►|</span>
    </div>
  );
}

// Handwritten Engineering Redline Note in Architects Daughter font
export function RedlineNote({ children, style, circle = false }) {
  return (
    <div className={`redline-note${circle ? ' redline-circled' : ''}`} style={style}>
      <span className="redline-arrow">↗</span>
      <span className="redline-text">{children}</span>
    </div>
  );
}

// Engineering Blueprint Title Block (Standard CAD Drawing Metadata Box)
export function TitleBlock({
  sheetNo = 'SEC-01',
  title = 'SPECIFICATION',
  scale = '1:1',
  rev = 'REV 2.4',
  project = 'SENTINEL-KMS',
  className = '',
  'aria-label': ariaLabel = 'Blueprint drawing metadata',
  ...titleBlockProps
}) {
  return (
    <dl
      {...titleBlockProps}
      className={`bp-title-block${className ? ` ${className}` : ''}`}
      aria-label={ariaLabel}
    >
      <div className="tb-col">
        <dt className="tb-label">PROJECT</dt>
        <dd className="tb-val">{project}</dd>
      </div>
      <div className="tb-col">
        <dt className="tb-label">DRAWING</dt>
        <dd className="tb-val">{title}</dd>
      </div>
      <div className="tb-col">
        <dt className="tb-label">SHEET NO.</dt>
        <dd className="tb-val cyan">{sheetNo}</dd>
      </div>
      <div className="tb-col">
        <dt className="tb-label">SCALE</dt>
        <dd className="tb-val">{scale}</dd>
      </div>
      <div className="tb-col">
        <dt className="tb-label">REVISION</dt>
        <dd className="tb-val redline">{rev}</dd>
      </div>
    </dl>
  );
}

// Blueprint Logo with Technical Registration Grid & Drafting Seal
export function Logo({ compact }) {
  return (
    <div className="logo bp-logo">
      <div className="logo-mark bp-logo-mark">
        <Icon name="shield" size={18} />
        <span className="bp-mark-cross tl">+</span>
        <span className="bp-mark-cross tr">+</span>
        <span className="bp-mark-cross bl">+</span>
        <span className="bp-mark-cross br">+</span>
      </div>
      {!compact && (
        <div className="logo-text">
          <div className="logo-head">
            <span className="logo-name">Sentinel</span>
            <span className="logo-grid-tag">v2.4</span>
          </div>
          <span className="logo-sub">SecOps Knowledge System</span>
        </div>
      )}
    </div>
  );
}

export function Pill({ children, tone = 'default', onClick, active, type = 'button', className = '', ...buttonProps }) {
  const ariaPressed = buttonProps['aria-pressed'] ?? (active !== undefined ? Boolean(active) : undefined);

  return (
    <button
      {...buttonProps}
      type={type}
      className={`pill pill-${tone}${active ? ' active' : ''}${className ? ` ${className}` : ''}`}
      onClick={onClick}
      aria-pressed={ariaPressed}
    >
      {children}
    </button>
  );
}

// Technical Stamp Badge ([ APPROVED ], [ DRAFT ], [ REDLINE ])
export function StampBadge({ text = 'APPROVED', tone = 'approved', className = '', ...badgeProps }) {
  return (
    <span {...badgeProps} className={`bp-stamp-badge tone-${tone}${className ? ` ${className}` : ''}`}>
      <span className="stamp-bracket" aria-hidden="true">[ </span>
      <span className="stamp-inner">{text}</span>
      <span className="stamp-bracket" aria-hidden="true"> ]</span>
    </span>
  );
}

// Semantic technical approval stamp button ([ ACTION // CONTEXT ])
export function StampButton({
  children,
  text,
  action = 'APPROVED',
  context = 'SEC-OPS',
  tone = 'approved',
  active,
  type = 'button',
  className = '',
  onClick,
  ...buttonProps
}) {
  const stampText = children ?? text ?? [action, context].filter(Boolean).join(' // ');
  const ariaPressed = buttonProps['aria-pressed'] ?? (active !== undefined ? Boolean(active) : undefined);

  return (
    <button
      {...buttonProps}
      type={type}
      className={`bp-stamp-badge bp-stamp-button tone-${tone}${active ? ' active' : ''}${className ? ` ${className}` : ''}`}
      onClick={onClick}
      aria-pressed={ariaPressed}
    >
      <span className="stamp-bracket" aria-hidden="true">[ </span>
      <span className="stamp-inner">{stampText}</span>
      <span className="stamp-bracket" aria-hidden="true"> ]</span>
    </button>
  );
}

// "cited source" chip used in chat + article relations.
export function SourceChip({
  id,
  onOpen,
  index,
  type = 'button',
  className = '',
  onClick,
  disabled = false,
  ...buttonProps
}) {
  const a = index && (index.get ? index.get(id) : index[id]);
  const isDisabled = disabled || (!onOpen && !onClick);

  function handleClick(event) {
    onClick && onClick(event);
    if (!event.defaultPrevented) onOpen && onOpen(id);
  }

  return (
    <button
      {...buttonProps}
      type={type}
      className={`source-chip bp-wire-chip${className ? ` ${className}` : ''}`}
      onClick={handleClick}
      disabled={isDisabled}
    >
      <span className="source-chip-prefix">REF//</span>
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
export function CopyBtn({
  text,
  className = '',
  label,
  size = 12,
  type = 'button',
  title = 'Copy text',
  onClick,
  disabled = false,
  ...buttonProps
}) {
  const [status, setStatus] = useState('idle');
  const resetTimerRef = useRef(null);
  const mountedRef = useRef(false);
  const attemptRef = useRef(0);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      attemptRef.current += 1;
      if (resetTimerRef.current !== null) window.clearTimeout(resetTimerRef.current);
    };
  }, []);

  function scheduleReset() {
    if (resetTimerRef.current !== null) window.clearTimeout(resetTimerRef.current);
    resetTimerRef.current = window.setTimeout(() => {
      if (mountedRef.current) setStatus('idle');
      resetTimerRef.current = null;
    }, 1300);
  }

  async function copy(event) {
    onClick && onClick(event);
    if (event.defaultPrevented) return;

    const attempt = ++attemptRef.current;
    if (resetTimerRef.current !== null) {
      window.clearTimeout(resetTimerRef.current);
      resetTimerRef.current = null;
    }
    setStatus('copying');

    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard API unavailable');
      await navigator.clipboard.writeText(String(text ?? ''));
      if (!mountedRef.current || attempt !== attemptRef.current) return;
      setStatus('copied');
    } catch {
      if (!mountedRef.current || attempt !== attemptRef.current) return;
      setStatus('error');
    }

    scheduleReset();
  }

  const done = status === 'copied';
  const failed = status === 'error';
  const visualLabel = done ? 'COPIED' : failed ? 'COPY FAILED' : label;
  const statusMessage = status === 'copying'
    ? 'Copying text'
    : done
      ? 'Text copied'
      : failed
        ? 'Unable to copy text'
        : '';
  const ariaLabel = buttonProps['aria-label'] || (label == null ? 'Copy text' : undefined);
  const copyClassName = [className, done && 'copied', failed && 'copy-failed'].filter(Boolean).join(' ');

  return (
    <button
      {...buttonProps}
      type={type}
      className={copyClassName}
      onClick={copy}
      title={title}
      disabled={disabled}
      aria-label={ariaLabel}
      aria-busy={buttonProps['aria-busy'] ?? (status === 'copying')}
      data-copy-status={status}
    >
      <Icon name={done ? 'check' : failed ? 'x' : 'copy'} size={size} />
      {label != null && <span>{visualLabel}</span>}
      <span role="status" aria-live="polite" aria-atomic="true" style={VISUALLY_HIDDEN}>{statusMessage}</span>
    </button>
  );
}

// Real-time Cursor Coordinate HUD tracker
export function CursorCoordinateHUD() {
  const [coords, setCoords] = useState({ x: 0, y: 0 });
  const pendingCoordsRef = useRef(coords);
  const frameRef = useRef(null);

  useEffect(() => {
    function handlePointerMove(event) {
      pendingCoordsRef.current = { x: event.clientX, y: event.clientY };
      if (frameRef.current !== null) return;

      frameRef.current = window.requestAnimationFrame(() => {
        frameRef.current = null;
        setCoords(pendingCoordsRef.current);
      });
    }

    window.addEventListener('pointermove', handlePointerMove, { passive: true });
    return () => {
      window.removeEventListener('pointermove', handlePointerMove);
      if (frameRef.current !== null) {
        window.cancelAnimationFrame(frameRef.current);
        frameRef.current = null;
      }
    };
  }, []);

  const xStr = String(coords.x).padStart(4, '0');
  const yStr = String(coords.y).padStart(4, '0');

  return (
    <div className="cursor-coord-hud" aria-hidden="true">
      <span className="coord-item"><span className="coord-k">X:</span><span className="coord-v">{xStr}px</span></span>
      <span className="coord-sep">|</span>
      <span className="coord-item"><span className="coord-k">Y:</span><span className="coord-v">{yStr}px</span></span>
      <span className="coord-sep">|</span>
      <span className="coord-item"><span className="coord-k">GRID:</span><span className="coord-v">40PX</span></span>
      <span className="coord-sep">|</span>
      <span className="coord-item"><span className="coord-k">THREAT:</span><span className="coord-v">TRACKED</span></span>
    </div>
  );
}
