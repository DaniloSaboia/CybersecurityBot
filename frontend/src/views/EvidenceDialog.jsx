// views/EvidenceDialog.jsx — fail-closed immutable evidence snapshot viewer.

import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '../icons.jsx';
import { getEvidenceView } from '../lib/api.js';

const CORE_FIELDS = [
  'articleId',
  'articleRevisionId',
  'revisionNumber',
  'evidenceSnapshotId',
  'sourceSpanStart',
  'sourceSpanEnd',
];

function isCleanIdentityString(value) {
  return typeof value === 'string' && Boolean(value.trim()) && value.trim() === value;
}

// This validation is intentionally strict. Invalid/source-only provenance gets
// the same unavailable UI state as a revoked or inaccessible reference.
export function isExactEvidenceReference(reference) {
  if (!reference
    || !isCleanIdentityString(reference.articleId)
    || !isCleanIdentityString(reference.articleRevisionId)
    || !isCleanIdentityString(reference.evidenceSnapshotId)) return false;
  return Number.isSafeInteger(reference.revisionNumber)
    && reference.revisionNumber > 0
    && Number.isSafeInteger(reference.sourceSpanStart)
    && reference.sourceSpanStart >= 0
    && Number.isSafeInteger(reference.sourceSpanEnd)
    && reference.sourceSpanEnd > reference.sourceSpanStart;
}

export function evidenceReferenceKey(reference) {
  return CORE_FIELDS.map((field) => String(reference?.[field] ?? '')).join('\u001f');
}

function isMatchingEvidenceResponse(reference, value) {
  return isExactEvidenceReference(reference)
    && value
    && typeof value.passage === 'string'
    && CORE_FIELDS.every((field) => value[field] === reference[field]);
}

// A returned URI is display metadata only. Even then, make an external link
// only for absolute HTTP(S) URLs without credentials/userinfo.
export function safeSourceUri(value) {
  if (typeof value !== 'string' || !value) return null;
  try {
    const url = new URL(value);
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) return null;
    return url.href;
  } catch {
    return null;
  }
}

function sourceDisplay(value) {
  return typeof value === 'string' && value ? value : 'No canonical source URI returned';
}

function focusables(container) {
  if (!container) return [];
  return Array.from(container.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  ));
}

export default function EvidenceDialog({ reference, userId, onClose, returnFocusRef }) {
  const [state, setState] = useState({ phase: 'loading', evidence: null });
  const dialogRef = useRef(null);
  const closeButtonRef = useRef(null);
  const requestIdRef = useRef(0);
  const focusOriginRef = useRef(null);
  const referenceKey = useMemo(() => evidenceReferenceKey(reference), [reference]);

  // The dialog is portaled outside #root, allowing the whole protected shell
  // to be genuinely inert while a snapshot is visible. aria-modal alone is
  // not enough to stop background scrolling or every assistive technology
  // from reaching the transcript behind the dialog.
  useEffect(() => {
    const appRoot = document.getElementById('root');
    const previousBodyOverflow = document.body.style.overflow;
    const previousHtmlOverflow = document.documentElement.style.overflow;
    const hadInert = appRoot?.hasAttribute('inert');
    const hadAriaHidden = appRoot?.hasAttribute('aria-hidden');
    const previousAriaHidden = appRoot?.getAttribute('aria-hidden');

    document.body.style.overflow = 'hidden';
    document.documentElement.style.overflow = 'hidden';
    if (appRoot) {
      appRoot.setAttribute('inert', '');
      appRoot.setAttribute('aria-hidden', 'true');
    }

    return () => {
      document.body.style.overflow = previousBodyOverflow;
      document.documentElement.style.overflow = previousHtmlOverflow;
      if (!appRoot) return;
      if (!hadInert) appRoot.removeAttribute('inert');
      if (hadAriaHidden) appRoot.setAttribute('aria-hidden', previousAriaHidden ?? 'true');
      else appRoot.removeAttribute('aria-hidden');
    };
  }, []);

  useEffect(() => {
    focusOriginRef.current = returnFocusRef?.current || document.activeElement;
    closeButtonRef.current?.focus();
    return () => {
      const target = returnFocusRef?.current || focusOriginRef.current;
      if (target && document.contains(target) && typeof target.focus === 'function') target.focus();
    };
    // The origin must be captured for this dialog instance, not changed when
    // a response changes its metadata.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const controller = new AbortController();
    if (!isExactEvidenceReference(reference)) {
      setState({ phase: 'unavailable', evidence: null });
      return () => controller.abort();
    }

    setState({ phase: 'loading', evidence: null });
    async function loadEvidence() {
      try {
        // Await inside try/catch so an unexpected synchronous client
        // validation failure takes the same non-disclosing path as a 400/404.
        const value = await getEvidenceView(reference, { signal: controller.signal });
        if (requestIdRef.current !== requestId) return;
        if (!isMatchingEvidenceResponse(reference, value)) {
          setState({ phase: 'unavailable', evidence: null });
          return;
        }
        setState({ phase: 'ready', evidence: value });
      } catch (error) {
        // Aborted owners are never allowed to overwrite a successor user’s
        // dialog. All non-401 server/local failures deliberately collapse to
        // a generic unavailable display; detail would leak existence/state.
        if (error?.name === 'AbortError' || requestIdRef.current !== requestId) return;
        setState({ phase: 'unavailable', evidence: null });
      }
    }
    void loadEvidence();

    return () => {
      controller.abort();
      requestIdRef.current += 1;
    };
  }, [reference, referenceKey, userId]);

  function handleKeyDown(event) {
    // Prevent the shell’s document-level command palette shortcut from
    // escaping this modal while it owns focus.
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = focusables(dialogRef.current);
    if (!items.length) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  // Capture Escape even if focus was moved outside by a browser extension or
  // an asynchronous repaint. Capture phase also prevents the shell shortcut.
  useEffect(() => {
    function documentKeyDown(event) {
      if (event.key !== 'Escape') return;
      event.preventDefault();
      event.stopPropagation();
      onClose();
    }
    document.addEventListener('keydown', documentKeyDown, true);
    return () => document.removeEventListener('keydown', documentKeyDown, true);
  }, [onClose]);

  const evidence = state.evidence;
  const safeUri = evidence && safeSourceUri(evidence.sourceUri);

  const dialog = (
    <div
      className="evidence-backdrop"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}
    >
      <section
        ref={dialogRef}
        className="evidence-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="exact-evidence-title"
        aria-describedby="exact-evidence-description"
        onKeyDown={handleKeyDown}
      >
        <header className="evidence-dialog-head">
          <div>
            <div className="evidence-kicker"><Icon name="link" size={12} /> IMMUTABLE SNAPSHOT // VERIFIED AT OPEN</div>
            <h2 id="exact-evidence-title">Exact evidence</h2>
          </div>
          <button ref={closeButtonRef} type="button" className="evidence-close" onClick={onClose} aria-label="Close exact evidence">
            <Icon name="x" size={16} />
          </button>
        </header>

        <div className="evidence-dialog-body" id="exact-evidence-description">
          {state.phase === 'loading' && (
            <div className="evidence-loading" role="status">
              <span className="evidence-pulse" aria-hidden="true" /> Resolving immutable evidence snapshot…
            </div>
          )}

          {state.phase === 'unavailable' && (
            <div className="evidence-unavailable" role="status">
              <Icon name="shield" size={16} />
              <div>
                <strong>Evidence record unavailable</strong>
                <p>The cited snapshot cannot be opened from this session.</p>
              </div>
            </div>
          )}

          {state.phase === 'ready' && evidence && (
            <>
              <p className="evidence-explainer">This is the immutable passage bound to the cited revision, not the current article body.</p>
              <blockquote className="evidence-passage" data-testid="exact-evidence-passage">{evidence.passage}</blockquote>
              <dl className="evidence-meta">
                <div><dt>ARTICLE</dt><dd><code>{evidence.articleId}</code></dd></div>
                <div><dt>REVISION</dt><dd><code>#{evidence.revisionNumber}</code> <span>{typeof evidence.revisionStatus === 'string' && evidence.revisionStatus ? evidence.revisionStatus : 'UNSPECIFIED'}</span></dd></div>
                <div><dt>SNAPSHOT</dt><dd><code>{evidence.evidenceSnapshotId}</code></dd></div>
                <div><dt>SPAN</dt><dd><code>[{evidence.sourceSpanStart}, {evidence.sourceSpanEnd})</code></dd></div>
                <div><dt>SOURCE VERSION</dt><dd>{typeof evidence.sourceVersion === 'string' && evidence.sourceVersion ? evidence.sourceVersion : 'Not supplied'}</dd></div>
              </dl>
              <div className="evidence-source">
                <span>CANONICAL SOURCE</span>
                {safeUri ? (
                  <a href={safeUri} target="_blank" rel="noopener noreferrer">Open canonical source</a>
                ) : (
                  <span className="evidence-source-text">{sourceDisplay(evidence.sourceUri)}</span>
                )}
              </div>
            </>
          )}
        </div>
      </section>
    </div>
  );

  return createPortal(dialog, document.body);
}
