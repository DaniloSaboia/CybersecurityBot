// @vitest-environment jsdom
// lib/api.test.js — unit tests for the HTTP/SSE client and authorization errors.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import {
  ApiError,
  SESSION_EXPIRED_EVENT,
  getMe,
  getEvidenceView,
  parseSseFrame,
  chatStream,
  setActiveSessionExpiryGeneration,
} from './api.js';

vi.mock('./supabase.js', () => ({
  supabase: {
    auth: {
      getSession: vi.fn(async () => ({
        data: {
          session: {
            access_token: 'opaque-test-bearer',
            user: { id: 'analyst-a', email: 'analyst-a@example.test' },
          },
        },
      })),
    },
  },
}));

describe('parseSseFrame', () => {
  it('parses a data frame with JSON', () => {
    expect(parseSseFrame('data: {"type":"delta","text":"hi"}')).toEqual({ type: 'delta', text: 'hi' });
  });

  it('handles event-name lines before data', () => {
    expect(parseSseFrame('event: message\ndata: {"type":"done","citations":[]}')).toEqual({
      type: 'done',
      citations: [],
    });
  });

  it('returns null for keep-alives and malformed JSON', () => {
    expect(parseSseFrame('')).toBeNull();
    expect(parseSseFrame(': ping')).toBeNull();
    expect(parseSseFrame('data: not-json')).toBeNull();
  });
});

function sseResponse(frames) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      for (const f of frames) controller.enqueue(encoder.encode(f));
      controller.close();
    },
  });
  return new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } });
}

afterEach(() => {
  vi.unstubAllGlobals();
  setActiveSessionExpiryGeneration(null);
});

beforeEach(() => {
  setActiveSessionExpiryGeneration({
    access_token: 'opaque-test-bearer',
    user: { id: 'analyst-a', email: 'analyst-a@example.test' },
  });
});

describe('chatStream', () => {
  it('emits deltas in order then the done event', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      sseResponse([
        'data: {"type":"delta","text":"Step "}\n\n',
        'data: {"type":"delta","text":"one"}\n\n',
        ': keep-alive\n\n',
        'data: {"type":"done","conversationId":"c-1","messageId":"m-1","citations":["TI-2291"]}\n\n',
      ])));

    const deltas = [];
    let done = null;
    await chatStream({ message: 'q', conversationId: 'c-1' }, {
      onDelta: (t) => deltas.push(t),
      onDone: (evt) => { done = evt; },
    });

    expect(deltas).toEqual(['Step ', 'one']);
    expect(done).toEqual({ type: 'done', conversationId: 'c-1', messageId: 'm-1', citations: ['TI-2291'] });
  });

  it('handles frames split across chunks', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      sseResponse(['data: {"type":"del', 'ta","text":"x"}\n\ndata: {"type":"done","citations":[]}\n\n'])));

    const deltas = [];
    let sawDone = false;
    await chatStream({ message: 'q' }, {
      onDelta: (t) => deltas.push(t),
      onDone: () => { sawDone = true; },
    });
    expect(deltas).toEqual(['x']);
    expect(sawDone).toBe(true);
  });

  it('reports failures via onError', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new Error('network down');
    }));
    const onError = vi.fn();
    await chatStream({ message: 'q' }, { onError });
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'network down' }));
  });
});

describe('HTTP authorization errors', () => {
  it('turns a 401 into a readable error and announces session expiry', async () => {
    let expiryEvent;
    const onSessionExpired = (event) => { expiryEvent = event; };
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    vi.stubGlobal('fetch', vi.fn(async () => new Response('token is expired', {
      status: 401,
      statusText: 'Unauthorized',
    })));

    try {
      const error = await getMe().catch((err) => err);
      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status: 401 });
      expect(error.message).toMatch(/session expired/i);
      expect(expiryEvent).toBeTruthy();
      expect(expiryEvent.detail).toEqual({ generation: expect.any(Number) });
      expect(JSON.stringify(expiryEvent.detail)).not.toContain('opaque-test-bearer');
    } finally {
      window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    }
  });

  it('turns a 403 into a readable permission error without ending the session', async () => {
    const onSessionExpired = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    vi.stubGlobal('fetch', vi.fn(async () => new Response('admin role required', {
      status: 403,
      statusText: 'Forbidden',
    })));

    try {
      const error = await getMe().catch((err) => err);
      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status: 403 });
      expect(error.message).toMatch(/permission denied/i);
      expect(onSessionExpired).not.toHaveBeenCalled();
    } finally {
      window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    }
  });
});

describe('exact evidence view HTTP contract', () => {
  const reference = {
    articleId: 'article/1',
    articleRevisionId: 'revision/7',
    revisionNumber: 7,
    evidenceSnapshotId: 'snapshot&x',
    sourceSpanStart: 4,
    sourceSpanEnd: 12,
    // These are response-only metadata. Passing them here must not make them
    // request authority or query parameters.
    sourceUri: 'https://authority.example/evidence',
    sourceVersion: 'v7',
    revisionStatus: 'APPROVED',
  };

  it('sends only the six immutable evidence identity fields and forwards cancellation', async () => {
    const controller = new AbortController();
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ passage: 'immutable slice' }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    })));

    await expect(getEvidenceView(reference, { signal: controller.signal })).resolves.toEqual({ passage: 'immutable slice' });

    expect(fetch).toHaveBeenCalledWith(
      '/api/kb/evidence/view?articleId=article%2F1&articleRevisionId=revision%2F7&revisionNumber=7&evidenceSnapshotId=snapshot%26x&sourceSpanStart=4&sourceSpanEnd=12',
      expect.objectContaining({ method: 'GET', signal: controller.signal })
    );
    const [url] = fetch.mock.calls[0];
    expect(url).not.toContain('sourceUri');
    expect(url).not.toContain('sourceVersion');
    expect(url).not.toContain('revisionStatus');
  });

  it.each([
    [{ ...reference, articleId: '' }],
    [{ ...reference, articleRevisionId: undefined }],
    [{ ...reference, evidenceSnapshotId: '  ' }],
    [{ ...reference, revisionNumber: 0 }],
    [{ ...reference, revisionNumber: 1.5 }],
    [{ ...reference, sourceSpanStart: -1 }],
    [{ ...reference, sourceSpanEnd: 4 }],
    [{ ...reference, sourceSpanEnd: 3 }],
  ])('rejects malformed local evidence references without making a request', async (invalidReference) => {
    vi.stubGlobal('fetch', vi.fn());

    await expect(getEvidenceView(invalidReference)).rejects.toThrow(/invalid exact evidence reference/i);
    expect(fetch).not.toHaveBeenCalled();
  });
});
