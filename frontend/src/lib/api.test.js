// lib/api.test.js — unit tests for the SSE frame parser and chatStream client.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { parseSseFrame, chatStream } from './api.js';

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
