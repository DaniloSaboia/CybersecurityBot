// Assistant.test.jsx — local streams must die with their authenticated view.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { StrictMode, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Assistant from './Assistant.jsx';
import { chatStream, getEvidenceView } from '../lib/api.js';
import { queryClient } from '../lib/queries.js';

vi.mock('../lib/queries.js', () => ({
  queryClient: { invalidateQueries: vi.fn() },
  useSuggestions: () => ({ data: [], isPending: false, isError: false }),
  useArticleIndex: () => ({ index: new Map(), isPending: false, isError: false }),
  useConversations: () => ({ data: [], isPending: false, isError: false, isSuccess: true }),
  useVectorStoreStatus: () => ({ data: { chunks: 1 }, isPending: false, isError: false }),
}));

vi.mock('../lib/api.js', () => ({
  chatStream: vi.fn(),
  deleteConversation: vi.fn(),
  getConversationMessages: vi.fn(),
  getEvidenceView: vi.fn(),
  sendFeedback: vi.fn(),
}));

vi.mock('./SettingsModal.jsx', () => ({
  DEFAULT_MODELS: [{ id: 'openrouter/free', name: 'Free Models Router' }],
  getStoredModel: () => 'openrouter/free',
  getStoredRagParams: () => ({ topK: 6, minScore: 0.3, historyTurns: 4, category: '', minSeverity: '' }),
}));

describe('Assistant stream lifecycle', () => {
  beforeEach(() => {
    chatStream.mockReset();
    getEvidenceView.mockReset();
    queryClient.invalidateQueries.mockReset();
  });

  afterEach(() => cleanup());

  it('consumes a cross-view question once across StrictMode replay and a visit back from its citation', async () => {
    let callbacks;
    chatStream.mockImplementation((_request, nextCallbacks) => {
      callbacks = nextCallbacks;
      return Promise.resolve();
    });
    function CrossViewHarness() {
      const [pending, setPending] = useState({ n: 1, text: 'Help me use the phishing playbook.' });
      const [showAssistant, setShowAssistant] = useState(true);
      return <>
        <button onClick={() => setShowAssistant((visible) => !visible)}>Switch view</button>
        {showAssistant && <Assistant userId="analyst-a" pendingAsk={pending} onPendingAskConsumed={(consumed) => setPending((current) => current === consumed ? null : current)} onOpenArticle={vi.fn()} />}
      </>;
    }
    render(<StrictMode><CrossViewHarness /></StrictMode>);
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));
    expect(chatStream.mock.calls[0][0].signal.aborted).toBe(false);
    act(() => {
      callbacks.onDelta('Open the retrieved playbook.');
      callbacks.onDone({ conversationId: 'conversation-1', messageId: 'message-1', citations: ['PB-START-002'] });
    });

    fireEvent.click(screen.getByRole('button', { name: 'Switch view' }));
    fireEvent.click(screen.getByRole('button', { name: 'Switch view' }));
    await act(async () => { await Promise.resolve(); });
    expect(chatStream).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('heading', { name: 'What happened?' })).toBeTruthy();
  });

  it('does not resend a completed pending question when the consumption callback is absent', async () => {
    let callbacks;
    chatStream.mockImplementation((_request, nextCallbacks) => {
      callbacks = nextCallbacks;
      return Promise.resolve();
    });
    render(<Assistant userId="analyst-a" pendingAsk={{ n: 1, text: 'Help me use this playbook.' }} onOpenArticle={vi.fn()} />);
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));
    act(() => callbacks.onDone({ citations: [] }));
    await act(async () => { await Promise.resolve(); });
    expect(chatStream).toHaveBeenCalledTimes(1);
  });

  it('uses the reported incident context to request a practical response plan', () => {
    render(<Assistant userId="analyst-a" onOpenArticle={vi.fn()} />);
    fireEvent.click(screen.getByText('Build a response plan'));
    const submit = screen.getByRole('button', { name: 'Get response plan' });
    expect(submit.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/What happened\?/), { target: { value: 'I entered my password on a suspicious sign-in page.' } });
    fireEvent.change(screen.getByLabelText('What is affected?'), { target: { value: 'My work email account' } });
    fireEvent.change(screen.getByLabelText('What have you already done?'), { target: { value: 'Contacted IT' } });
    fireEvent.click(submit);

    expect(chatStream).toHaveBeenCalledTimes(1);
    const request = chatStream.mock.calls[0][0];
    expect(request.message).toContain('What happened: I entered my password on a suspicious sign-in page.');
    expect(request.message).toContain('Affected devices, accounts, or services: My work email account');
    expect(request.message).toContain('Actions already taken: Contacted IT');
    expect(request.message).toContain('how to verify recovery');
    expect(request.message).toContain('cite your sources');
  });

  it('keeps missing incident details explicit instead of inventing affected systems or actions', () => {
    render(<Assistant userId="analyst-a" onOpenArticle={vi.fn()} />);
    fireEvent.click(screen.getByText('Build a response plan'));
    fireEvent.change(screen.getByLabelText(/What happened\?/), { target: { value: 'Files cannot be opened.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Get response plan' }));

    const request = chatStream.mock.calls[0][0];
    expect(request.message).toContain('Affected devices, accounts, or services: Not yet known.');
    expect(request.message).toContain('Actions already taken: Not specified.');
    expect(request.message).toContain('do not assume missing facts');
  });

  it('aborts an active stream on unmount and ignores its late completion', async () => {
    let request;
    let callbacks;
    chatStream.mockImplementation((nextRequest, nextCallbacks) => {
      request = nextRequest;
      callbacks = nextCallbacks;
      return Promise.resolve();
    });

    const { unmount } = render(<Assistant userId="analyst-a" onOpenArticle={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Technical query' }), { target: { value: 'A-only incident detail' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send technical query' }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));

    expect(request.signal?.aborted).toBe(false);
    unmount();
    expect(request.signal?.aborted).toBe(true);

    act(() => callbacks.onDone({ conversationId: 'conversation-a', messageId: 'message-a', citations: ['CVE-A'] }));
    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();
  });

  it('keeps legacy article citations separate from snapshot-bound exact evidence', async () => {
    let callbacks;
    chatStream.mockImplementation((_request, nextCallbacks) => {
      callbacks = nextCallbacks;
      return Promise.resolve();
    });
    getEvidenceView.mockResolvedValue({
      articleId: 'CVE-2025-1111',
      articleRevisionId: 'revision-7',
      revisionNumber: 7,
      evidenceSnapshotId: 'snapshot-7',
      sourceSpanStart: 0,
      sourceSpanEnd: 8,
      sourceUri: 'https://evidence.example/advisories/1',
      sourceVersion: '7',
      revisionStatus: 'APPROVED',
      passage: 'snapshot',
    });

    render(<Assistant userId="analyst-a" onOpenArticle={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Technical query' }), { target: { value: 'Show immutable proof' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send technical query' }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));
    act(() => callbacks.onDone({
      conversationId: 'conversation-a',
      messageId: 'message-a',
      citations: ['CVE-2025-1111'],
      citationReferences: [{
        articleId: 'CVE-2025-1111',
        articleRevisionId: 'revision-7',
        revisionNumber: 7,
        evidenceSnapshotId: 'snapshot-7',
        sourceSpanStart: 0,
        sourceSpanEnd: 8,
      }],
    }));

    await waitFor(() => expect(screen.getAllByText('CVE-2025-1111').length).toBe(2));
    const evidenceButton = screen.getByRole('button', { name: /view exact evidence/i });
    expect(evidenceButton).toBeTruthy();
    fireEvent.click(evidenceButton);
    await waitFor(() => expect(screen.getByRole('dialog', { name: 'Exact evidence' })).toBeTruthy());
    expect(getEvidenceView).toHaveBeenCalledTimes(1);
  });

  it('formats response Markdown while waiting for server metadata before making inline sources clickable', async () => {
    let callbacks;
    chatStream.mockImplementation((_request, nextCallbacks) => {
      callbacks = nextCallbacks;
      return Promise.resolve();
    });
    const onOpenArticle = vi.fn();
    const revision = '3df31b12-04a3-4b33-ad21-b0944b37fdaf';
    const { container } = render(<Assistant userId="analyst-a" onOpenArticle={onOpenArticle} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Technical query' }), { target: { value: 'My API key was exposed' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send technical query' }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));
    act(() => callbacks.onDelta(`# Revoking exposed API keys\n\n## Immediate actions\n1. Review the provider console. [PB-START-007:${revision}]\n\n2. Verify recovery.`));

    expect(screen.getByRole('heading', { name: 'Revoking exposed API keys', level: 2 })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Open source 1: PB-START-007' })).toBeNull();
    expect(screen.getByRole('note', { name: /Unverified source reference/ })).toBeTruthy();
    expect([...container.querySelectorAll('.msg-steps > li')].map((item) => item.value)).toEqual([1, 2]);

    act(() => callbacks.onDone({
      conversationId: 'conversation-a', messageId: 'message-a', citations: [],
      citationReferences: [{
        articleId: 'PB-START-007', articleRevisionId: revision, revisionNumber: 1,
        evidenceSnapshotId: 'snapshot-1', sourceSpanStart: 0, sourceSpanEnd: 85,
      }],
    }));

    const articleCitation = screen.getByRole('button', { name: 'Open source 1: PB-START-007' });
    expect(articleCitation.textContent).toBe('1');
    fireEvent.click(articleCitation);
    expect(onOpenArticle).toHaveBeenCalledWith('PB-START-007');
    expect(screen.getByRole('button', { name: 'View exact evidence for PB-START-007, revision 1' })).toBeTruthy();
    expect(getEvidenceView).not.toHaveBeenCalled();
  });

  it('removes an A-owned evidence dialog and aborts its request before B can render it', async () => {
    let callbacks;
    let evidenceSignal;
    chatStream.mockImplementation((_request, nextCallbacks) => {
      callbacks = nextCallbacks;
      return Promise.resolve();
    });
    getEvidenceView.mockImplementation((_reference, { signal }) => {
      evidenceSignal = signal;
      return new Promise(() => {});
    });

    const { rerender } = render(<Assistant userId="analyst-a" onOpenArticle={vi.fn()} />);
    fireEvent.change(screen.getByRole('textbox', { name: 'Technical query' }), { target: { value: 'A-only evidence request' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send technical query' }));
    await waitFor(() => expect(chatStream).toHaveBeenCalledTimes(1));
    act(() => callbacks.onDone({
      citations: [],
      citationReferences: [{
        articleId: 'CVE-A',
        articleRevisionId: 'revision-a',
        revisionNumber: 1,
        evidenceSnapshotId: 'snapshot-a',
        sourceSpanStart: 0,
        sourceSpanEnd: 8,
      }],
    }));
    const evidenceButton = await screen.findByRole('button', { name: /view exact evidence for CVE-A/i });
    fireEvent.click(evidenceButton);
    await waitFor(() => expect(getEvidenceView).toHaveBeenCalledTimes(1));

    rerender(<Assistant userId="analyst-b" onOpenArticle={vi.fn()} />);
    expect(evidenceSignal.aborted).toBe(true);
    expect(screen.queryByRole('dialog', { name: 'Exact evidence' })).toBeNull();
  });
});
