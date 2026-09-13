// Assistant.test.jsx — local streams must die with their authenticated view.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
