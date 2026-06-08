// lib/api.js — fetch wrapper around the Sentinel KMS backend.
// All calls are same-origin under BASE (the Vite dev server proxies /api ->
// backend) and carry the Supabase access token as a Bearer header.

import { supabase } from './supabase.js';

const BASE = import.meta.env.VITE_API_BASE || '/api';

async function authHeader() {
  const { data } = await supabase.auth.getSession();
  const token = data?.session?.access_token;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// core request helper: throws on non-2xx with the response text, parses JSON otherwise
async function request(path, { method = 'GET', body, headers, isForm } = {}) {
  const opts = { method, headers: { ...(await authHeader()), ...headers } };
  if (body !== undefined) {
    if (isForm) {
      opts.body = body; // FormData — browser sets the multipart boundary
    } else {
      opts.headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
  }
  const res = await fetch(BASE + path, opts);
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `${res.status} ${res.statusText}`);
  }
  if (res.status === 204) return null;
  const ct = res.headers.get('content-type') || '';
  if (ct.includes('application/json')) return res.json();
  return res.text();
}

function qs(params) {
  const sp = new URLSearchParams();
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v != null && v !== '' && v !== 'all') sp.set(k, v);
  });
  const s = sp.toString();
  return s ? `?${s}` : '';
}

// ── Session / shell ──────────────────────────────────────────────
export function getMe() {
  return request('/me');
}

// ── Knowledge Base ───────────────────────────────────────────────
export function getCategories() {
  return request('/kb/categories');
}

// returns { items: Article[], total: number }
export function getArticles({ q, category, severity, sort } = {}) {
  return request('/kb/articles' + qs({ q, category, severity, sort }));
}

export function getArticle(id) {
  return request('/kb/articles/' + encodeURIComponent(id));
}

// ── Assistant ────────────────────────────────────────────────────
export function getSuggestions() {
  return request('/assistant/suggestions');
}

// Non-streaming structured answer (kept for fallback/testing).
export function chat({ message, conversationId } = {}) {
  return request('/assistant/chat', { method: 'POST', body: { message, conversationId } });
}

// Streaming answer over SSE. Calls onDelta(textChunk) as tokens arrive and
// onDone({ conversationId, citations }) at the end. Reads the response body as
// a stream and parses `data: <json>` SSE frames.
export async function chatStream({ message, conversationId }, { onDelta, onDone, onError } = {}) {
  try {
    const res = await fetch(BASE + '/assistant/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(await authHeader()) },
      body: JSON.stringify({ message, conversationId }),
    });
    if (!res.ok || !res.body) {
      const text = await res.text().catch(() => '');
      throw new Error(text || `stream failed: ${res.status}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    let sawDone = false;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let sep;
      while ((sep = buf.indexOf('\n\n')) !== -1) {
        const frame = buf.slice(0, sep);
        buf = buf.slice(sep + 2);
        const dataLine = frame.split('\n').find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        const json = dataLine.slice(5).trim();
        if (!json) continue;
        let evt;
        try { evt = JSON.parse(json); } catch { continue; }
        if (evt.type === 'delta') onDelta && onDelta(evt.text || '');
        else if (evt.type === 'done') { sawDone = true; onDone && onDone(evt); }
      }
    }
    if (!sawDone) onDone && onDone({ citations: [] });
  } catch (err) {
    onError && onError(err);
  }
}

// ── Ingestion ────────────────────────────────────────────────────
export function getJobs() {
  return request('/ingest/jobs');
}

export function uploadFile(file) {
  const fd = new FormData();
  fd.append('file', file);
  return request('/ingest/upload', { method: 'POST', body: fd, isForm: true });
}

export function addUrl(url) {
  return request('/ingest/url', { method: 'POST', body: { url } });
}

export function retryJob(id) {
  return request('/ingest/jobs/' + encodeURIComponent(id) + '/retry', { method: 'POST' });
}

export function getConnectors() {
  return request('/connectors');
}

// Trigger a connector sync (NVD CVE feed). Returns { fetched, created, embedded }.
export function syncConnector(id, { days = 7, max = 15 } = {}) {
  return request('/connectors/' + encodeURIComponent(id) + '/sync' + qs({ days, max }), { method: 'POST' });
}

// { engine, embeddingModel, chunks, dim, p95Ms, healthy }
export function getVectorStoreStatus() {
  return request('/vectorstore/status');
}
