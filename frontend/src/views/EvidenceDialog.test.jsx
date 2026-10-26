// @vitest-environment jsdom
// Exact evidence must remain snapshot-bound, cancellable, and safe to open.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import EvidenceDialog, { safeSourceUri } from './EvidenceDialog.jsx';
import { getEvidenceView } from '../lib/api.js';

vi.mock('../lib/api.js', () => ({
  getEvidenceView: vi.fn(),
}));

const reference = {
  articleId: 'CVE-2025-1111',
  articleRevisionId: 'revision-7',
  revisionNumber: 7,
  evidenceSnapshotId: 'snapshot-immutable-7',
  sourceSpanStart: 4,
  sourceSpanEnd: 28,
};

function response(overrides = {}) {
  return {
    ...reference,
    sourceUri: 'https://evidence.example/advisories/CVE-2025-1111',
    sourceVersion: '2026.09.05',
    revisionStatus: 'APPROVED',
    passage: 'immutable snapshot passage',
    ...overrides,
  };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}

function renderDialog(props = {}) {
  const appRoot = document.createElement('div');
  appRoot.id = 'root';
  appRoot.dataset.evidenceTestRoot = 'true';
  const container = document.createElement('div');
  const opener = document.createElement('button');
  opener.textContent = 'Open source evidence';
  appRoot.append(opener, container);
  document.body.append(appRoot);
  opener.focus();
  const result = render(
    <EvidenceDialog
      reference={reference}
      userId="analyst-a"
      onClose={vi.fn()}
      returnFocusRef={{ current: opener }}
      {...props}
    />,
    { container }
  );
  return { ...result, appRoot, opener };
}

describe('EvidenceDialog', () => {
  beforeEach(() => getEvidenceView.mockReset());
  afterEach(() => {
    cleanup();
    document.querySelectorAll('[data-evidence-test-root]').forEach((element) => element.remove());
  });

  it('renders only the endpoint-returned immutable passage and revision provenance', async () => {
    getEvidenceView.mockResolvedValue(response());
    renderDialog();

    await waitFor(() => expect(screen.getByTestId('exact-evidence-passage').textContent).toContain('immutable snapshot passage'));
    expect(screen.getByRole('dialog', { name: 'Exact evidence' }).getAttribute('aria-modal')).toBe('true');
    expect(screen.getByText('2026.09.05')).toBeTruthy();
    expect(screen.getByText('APPROVED')).toBeTruthy();
    expect(screen.queryByText(/current mutable article body/i)).toBeNull();
    expect(getEvidenceView).toHaveBeenCalledWith(reference, expect.objectContaining({ signal: expect.any(AbortSignal) }));
  });

  it('uses one non-disclosing unavailable state for a forged or malformed response', async () => {
    getEvidenceView.mockResolvedValue(response({ articleRevisionId: 'forged-revision', passage: 'must not display' }));
    renderDialog();

    await waitFor(() => expect(screen.getByText('Evidence record unavailable')).toBeTruthy());
    expect(screen.queryByTestId('exact-evidence-passage')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.queryByText('must not display')).toBeNull();
  });

  it('does not create a link for an unsafe canonical source URI', async () => {
    getEvidenceView.mockResolvedValue(response({ sourceUri: 'javascript:alert(1)' }));
    renderDialog();

    await waitFor(() => expect(screen.getByTestId('exact-evidence-passage')).toBeTruthy());
    expect(screen.queryByRole('link', { name: 'Open canonical source' })).toBeNull();
    expect(screen.getByText('javascript:alert(1)')).toBeTruthy();
  });

  it('opens only credential-free absolute http or https sources in a new tab', async () => {
    getEvidenceView.mockResolvedValue(response({ sourceUri: 'https://evidence.example/advisories/1' }));
    renderDialog();

    const link = await screen.findByRole('link', { name: 'Open canonical source' });
    expect(link.getAttribute('href')).toBe('https://evidence.example/advisories/1');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it.each([
    ['javascript:alert(1)'],
    ['data:text/plain,untrusted'],
    ['/relative-source'],
    ['https://operator:secret@evidence.example/advisory'],
    ['http://operator@evidence.example/advisory'],
  ])('rejects an unsafe response source URI: %s', (uri) => {
    expect(safeSourceUri(uri)).toBeNull();
  });

  it('traps Tab focus inside the evidence dialog', async () => {
    getEvidenceView.mockResolvedValue(response());
    renderDialog();
    const close = await screen.findByRole('button', { name: 'Close exact evidence' });
    const link = await screen.findByRole('link', { name: 'Open canonical source' });
    await waitFor(() => expect(document.activeElement).toBe(close));

    link.focus();
    fireEvent.keyDown(link, { key: 'Tab' });
    expect(document.activeElement).toBe(close);
    close.focus();
    fireEvent.keyDown(close, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(link);
  });

  it('does not request an incomplete or source-only citation reference', async () => {
    renderDialog({ reference: { sourceUri: 'https://untrusted.example/upload' } });

    await waitFor(() => expect(screen.getByText('Evidence record unavailable')).toBeTruthy());
    expect(getEvidenceView).not.toHaveBeenCalled();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('restores focus and closes on Escape', async () => {
    getEvidenceView.mockResolvedValue(response());
    const { opener, unmount } = renderDialog();
    const close = await screen.findByRole('button', { name: 'Close exact evidence' });
    await waitFor(() => expect(document.activeElement).toBe(close));

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.getByRole('dialog', { name: 'Exact evidence' })).toBeTruthy();
    unmount();
    expect(document.activeElement).toBe(opener);
  });

  it('locks document scroll and isolates the protected app root while open, then restores both', async () => {
    getEvidenceView.mockResolvedValue(response());
    const { appRoot, opener, unmount } = renderDialog();
    await screen.findByRole('dialog', { name: 'Exact evidence' });

    expect(document.body.style.overflow).toBe('hidden');
    expect(document.documentElement.style.overflow).toBe('hidden');
    expect(appRoot.hasAttribute('inert')).toBe(true);
    expect(appRoot.getAttribute('aria-hidden')).toBe('true');

    unmount();

    expect(document.body.style.overflow).toBe('');
    expect(document.documentElement.style.overflow).toBe('');
    expect(appRoot.hasAttribute('inert')).toBe(false);
    expect(appRoot.hasAttribute('aria-hidden')).toBe(false);
    expect(document.activeElement).toBe(opener);
  });

  it('aborts and ignores a late A response after the active user changes', async () => {
    const a = deferred();
    getEvidenceView.mockImplementationOnce((_reference, { signal }) => {
      a.signal = signal;
      return a.promise;
    });
    getEvidenceView.mockResolvedValueOnce(response({ passage: 'B immutable passage' }));
    const { rerender } = renderDialog();
    await waitFor(() => expect(getEvidenceView).toHaveBeenCalledTimes(1));

    rerender(<EvidenceDialog reference={reference} userId="analyst-b" onClose={vi.fn()} />);
    await waitFor(() => expect(getEvidenceView).toHaveBeenCalledTimes(2));
    expect(a.signal.aborted).toBe(true);

    await act(async () => a.resolve(response({ passage: 'A-only passage' })));
    expect(screen.queryByText('A-only passage')).toBeNull();
    await waitFor(() => expect(screen.getByText('B immutable passage')).toBeTruthy());
  });
});
