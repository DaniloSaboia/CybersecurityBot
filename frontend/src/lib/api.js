// lib/api.js — thin fetch wrapper around the Sentinel KMS backend.
// All calls are same-origin under BASE; the Vite dev server proxies /api -> backend.

const BASE = import.meta.env.VITE_API_BASE || '/api';

// core request helper: throws on non-2xx with the response text, parses JSON otherwise
async function request(path, { method = 'GET', body, headers, isForm } = {}) {
  const opts = { method, headers: { ...headers } };
  if (body !== undefined) {
    if (isForm) {
      opts.body = body; // FormData — let the browser set the multipart boundary
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

// { conversationId, blocks[], citations[] }
export function chat({ message, conversationId } = {}) {
  return request('/assistant/chat', { method: 'POST', body: { message, conversationId } });
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

// { engine, embeddingModel, chunks, dim, p95Ms, healthy }
export function getVectorStoreStatus() {
  return request('/vectorstore/status');
}
