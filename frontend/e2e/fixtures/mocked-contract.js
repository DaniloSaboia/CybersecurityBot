// mocked-contract.js — deterministic browser fixture for frontend HTTP contracts.
//
// IMPORTANT: this deliberately intercepts GoTrue and /api requests. It proves
// the browser's request shapes and user-visible behavior only; it does NOT
// validate a JWT signature, backend authorization/RLS, persistence, or real
// streaming transport. Shell's backend suite owns those claims.

import { expect, test as base } from '@playwright/test';

export const MOCK_ACCESS_TOKEN = 'eyJhbGciOiJub25lIn0.eyJzdWIiOiJtb2NrZWQtYW5hbHlzdCIsImVtYWlsIjoiYW5hbHlzdEBleGFtcGxlLnRlc3QiLCJyb2xlIjoiYXV0aGVudGljYXRlZCIsImV4cCI6NDEwMjQ0NDgwMH0.mock-signature';

const FIXTURE_ARTICLE = {
  id: 'CVE-2025-1111',
  title: 'Fixture Credential Exposure Advisory',
  snippet: 'Rotate exposed credentials and apply the vendor remediation.',
  category: 'vulnerabilities',
  severity: 'high',
  cvss: 8.1,
  confidence: 'HIGH',
  source: 'Fixture threat feed',
  author: 'Mocked Contract Service',
  updated: '2026-09-05T00:00:00Z',
  reads: 7,
  tags: ['credential-rotation', 'fixture'],
  iocs: [],
  body: [{ h: 'Required response', p: 'Rotate affected credentials and apply the vendor remediation.' }],
  related: [],
};

// This belongs to the mocked HTTP contract only. It is intentionally separate
// from the legacy article id citation, so the browser proves it calls the
// immutable evidence-view route instead of deriving a quote from ArticleDto.
const FIXTURE_EVIDENCE_REFERENCE = {
  articleId: FIXTURE_ARTICLE.id,
  articleRevisionId: 'article-revision-fixture-7',
  revisionNumber: 7,
  evidenceSnapshotId: 'evidence-snapshot-fixture-7',
  sourceSpanStart: 0,
  sourceSpanEnd: 37,
};

const FIXTURE_EVIDENCE_VIEW = {
  ...FIXTURE_EVIDENCE_REFERENCE,
  sourceUri: 'https://evidence.example/advisories/CVE-2025-1111',
  sourceVersion: '2026.09.05',
  revisionStatus: 'APPROVED',
  sourceLabel: 'Fixture immutable advisory snapshot',
  passage: 'Apply the immutable vendor mitigation.',
};

function json(route, body, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

function text(route, body, status) {
  return route.fulfill({ status, contentType: 'text/plain', body });
}

function requestBody(request) {
  try {
    return JSON.parse(request.postData() || '{}');
  } catch {
    return {};
  }
}

function newWatchlist(input) {
  return {
    id: 'watch-fixture-1',
    label: input.label,
    terms: input.terms || [],
    minSeverity: input.minSeverity ?? null,
    unreadAlerts: 1,
    createdAt: '2026-09-05T00:00:00Z',
  };
}

export function createMockedContractState() {
  return {
    apiRequests: [],
    authRequests: [],
    uploads: [],
    retries: [],
    evidenceRequests: [],
    conversationMessageRequests: [],
    watchlistCreates: [],
    readAllCalls: 0,
    failures: new Map(),
    jobs: [{
      id: 'job-failed-1',
      name: 'failed-ingest.pdf',
      source: 'UPLOAD',
      size: '14 KB',
      stage: 'failed',
      pct: 42,
      chunks: 0,
      when: 'just now',
      error: 'Fixture parser fault',
    }],
    conversations: [],
    messages: new Map(),
    watchlists: [],
    alerts: [{
      id: 'alert-fixture-1',
      ownerSub: 'mocked-analyst',
      watchlistId: 'watch-fixture-1',
      articleId: FIXTURE_ARTICLE.id,
      articleTitle: FIXTURE_ARTICLE.title,
      severity: 'high',
      reason: 'Matched fixture term',
      read: false,
      createdAt: '2026-09-05T00:00:00Z',
    }],
    fail(method, path, status, body = '') {
      this.failures.set(`${method} ${path}`, { status, body });
    },
  };
}

async function handleGoTrue(route, state) {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname;
  state.authRequests.push({ method: request.method(), path });

  if (path.endsWith('/auth/v1/token') && request.method() === 'POST') {
    return json(route, {
      access_token: MOCK_ACCESS_TOKEN,
      token_type: 'bearer',
      expires_in: 3600,
      expires_at: 4102444800,
      refresh_token: 'mocked-refresh-token',
      user: {
        id: 'mocked-analyst',
        aud: 'authenticated',
        role: 'authenticated',
        email: 'analyst@example.test',
      },
    });
  }

  if (path.endsWith('/auth/v1/logout')) return route.fulfill({ status: 204 });
  if (path.endsWith('/auth/v1/user')) {
    return json(route, { id: 'mocked-analyst', email: 'analyst@example.test', role: 'authenticated' });
  }
  return text(route, 'Unmocked GoTrue request', 404);
}

async function handleApi(route, state) {
  const request = route.request();
  const url = new URL(request.url());
  const path = url.pathname.replace(/^\/api/, '') || '/';
  const method = request.method();
  const headers = request.headers();
  const call = {
    method,
    path,
    authorization: headers.authorization,
    contentType: headers['content-type'] || '',
    postData: request.postData() || '',
  };
  state.apiRequests.push(call);

  const forcedFailure = state.failures.get(`${method} ${path}`);
  if (forcedFailure) return text(route, forcedFailure.body, forcedFailure.status);
  if (call.authorization !== `Bearer ${MOCK_ACCESS_TOKEN}`) return text(route, 'Missing mocked bearer token', 401);

  if (method === 'GET' && path === '/me') {
    return json(route, { env: 'MOCKED CONTRACT', user: 'fixture analyst', role: 'Security Analyst' });
  }

  if (method === 'GET' && path === '/kb/categories') {
    return json(route, [{ id: 'vulnerabilities', label: 'Vulnerabilities', abbr: 'VULN', count: 1 }]);
  }
  if (method === 'GET' && path === '/kb/articles') return json(route, { items: [FIXTURE_ARTICLE], total: 1 });
  if (method === 'GET' && path === `/kb/articles/${FIXTURE_ARTICLE.id}`) return json(route, FIXTURE_ARTICLE);
  if (method === 'GET' && path === '/kb/evidence/view') {
    const fields = [
      'articleId',
      'articleRevisionId',
      'revisionNumber',
      'evidenceSnapshotId',
      'sourceSpanStart',
      'sourceSpanEnd',
    ];
    const received = Object.fromEntries(url.searchParams.entries());
    state.evidenceRequests.push({ keys: [...url.searchParams.keys()], received });
    const keysMatch = [...url.searchParams.keys()].length === fields.length
      && fields.every((field) => url.searchParams.has(field));
    const identityMatches = fields.every((field) => String(FIXTURE_EVIDENCE_REFERENCE[field]) === received[field]);
    if (!keysMatch) return text(route, 'Malformed exact evidence reference', 400);
    if (!identityMatches) return text(route, 'Unavailable exact evidence reference', 404);
    return json(route, FIXTURE_EVIDENCE_VIEW);
  }

  if (method === 'GET' && path === '/settings/models') return json(route, { models: [] });
  if (method === 'GET' && path === '/assistant/suggestions') return json(route, []);
  if (method === 'GET' && path === '/assistant/conversations') return json(route, state.conversations);

  if (method === 'GET' && path.startsWith('/assistant/conversations/') && path.endsWith('/messages')) {
    const id = path.split('/')[3];
    state.conversationMessageRequests.push(id);
    return json(route, state.messages.get(id) || []);
  }
  if (method === 'DELETE' && path.startsWith('/assistant/conversations/')) {
    const id = path.split('/')[3];
    state.conversations = state.conversations.filter((conversation) => conversation.id !== id);
    state.messages.delete(id);
    return route.fulfill({ status: 204 });
  }
  if (method === 'POST' && path.startsWith('/assistant/messages/') && path.endsWith('/feedback')) {
    return json(route, { id: path.split('/')[3], rating: requestBody(request).rating });
  }
  if (method === 'POST' && path === '/assistant/chat/stream') {
    const input = requestBody(request);
    const conversationId = input.conversationId || 'conversation-fixture-1';
    const messageId = 'message-fixture-1';
    if (!state.conversations.some((conversation) => conversation.id === conversationId)) {
      state.conversations = [{
        id: conversationId,
        title: input.message,
        updatedAt: '2026-09-05T00:00:00Z',
      }, ...state.conversations];
    }
    state.messages.set(conversationId, [
      { id: 'user-fixture-1', role: 'user', content: input.message, citations: [], rating: null, createdAt: '2026-09-05T00:00:00Z' },
      {
        id: messageId,
        role: 'assistant',
        content: 'Apply the vendor mitigation.',
        citations: [FIXTURE_ARTICLE.id],
        citationReferences: [FIXTURE_EVIDENCE_REFERENCE],
        rating: null,
        createdAt: '2026-09-05T00:00:01Z',
      },
    ]);
    return route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      // Deliberately contains two delta frames: the client must combine them.
      body: [
        'data: {"type":"delta","text":"Apply the "}\n\n',
        'data: {"type":"delta","text":"vendor mitigation."}\n\n',
        `data: {"type":"done","conversationId":"${conversationId}","messageId":"${messageId}","citations":["${FIXTURE_ARTICLE.id}"],"citationReferences":[${JSON.stringify(FIXTURE_EVIDENCE_REFERENCE)}]}\n\n`,
      ].join(''),
    });
  }

  if (method === 'GET' && path === '/ingest/jobs') return json(route, state.jobs);
  if (method === 'POST' && path === '/ingest/upload') {
    state.uploads.push(call);
    const uploaded = {
      id: 'job-upload-1',
      name: 'security-brief.pdf',
      source: 'UPLOAD',
      size: '25 B',
      stage: 'indexed',
      pct: 100,
      chunks: 3,
      when: 'just now',
    };
    state.jobs = [uploaded, ...state.jobs];
    return json(route, uploaded, 201);
  }
  if (method === 'POST' && path === '/ingest/jobs/job-failed-1/retry') {
    state.retries.push(call);
    state.jobs = state.jobs.map((job) => job.id === 'job-failed-1'
      ? { ...job, stage: 'indexed', pct: 100, chunks: 4, error: undefined }
      : job);
    return json(route, state.jobs.find((job) => job.id === 'job-failed-1'));
  }
  if (method === 'GET' && path === '/connectors') {
    return json(route, [{ id: 'nvd', name: 'NVD CVE Feed', kind: 'API', status: 'connected', docs: 1, last: 'just now', auto: true }]);
  }
  if (method === 'POST' && path === '/connectors/nvd/sync') return json(route, { fetched: 1, created: 1, embedded: 1 });
  if (method === 'GET' && path === '/vectorstore/status') {
    return json(route, { engine: 'fixture', embeddingModel: 'fixture-embed', chunks: 7, dim: 1024, p95Ms: 11, healthy: true });
  }

  if (method === 'GET' && path === '/watchlists') return json(route, state.watchlists);
  if (method === 'POST' && path === '/watchlists') {
    const watchlist = newWatchlist(requestBody(request));
    state.watchlists = [...state.watchlists, watchlist];
    state.watchlistCreates.push(watchlist);
    return json(route, watchlist, 201);
  }
  if (method === 'DELETE' && path.startsWith('/watchlists/')) {
    const id = path.split('/')[2];
    state.watchlists = state.watchlists.filter((watchlist) => watchlist.id !== id);
    return route.fulfill({ status: 200 });
  }
  if (method === 'GET' && path === '/alerts') {
    const unreadOnly = url.searchParams.get('unread') === 'true';
    return json(route, state.alerts.filter((alert) => !unreadOnly || !alert.read));
  }
  if (method === 'POST' && path === '/alerts/read-all') {
    const marked = state.alerts.filter((alert) => !alert.read).length;
    state.alerts = state.alerts.map((alert) => ({ ...alert, read: true }));
    state.readAllCalls += 1;
    return json(route, { marked });
  }
  if (method === 'POST' && path.startsWith('/alerts/') && path.endsWith('/read')) {
    const id = path.split('/')[2];
    state.alerts = state.alerts.map((alert) => (alert.id === id ? { ...alert, read: true } : alert));
    return route.fulfill({ status: 200 });
  }

  return text(route, `Unmocked API request: ${method} ${path}`, 404);
}

export const test = base.extend({
  mockedContract: async ({ page }, use) => {
    const state = createMockedContractState();
    await page.route('http://localhost:54321/**', (route) => handleGoTrue(route, state));
    await page.route('**/api/**', (route) => handleApi(route, state));
    await use(state);
  },
});

export { expect };

export async function signInThroughMockedContract(page) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Enter the Console' })).toBeVisible();
  await page.getByLabel('Operator email').fill('analyst@example.test');
  await page.getByLabel('Passcode').fill('mocked-contract-only');
  await page.getByRole('button', { name: 'Authenticate' }).click();
  await expect(page.getByRole('heading', { name: 'AI Assistant' })).toBeVisible();
}

export async function expectMockedBearerRequest(state, path) {
  await expect.poll(() => state.apiRequests.filter((call) => call.path === path).length).toBeGreaterThan(0);
  const calls = state.apiRequests.filter((call) => call.path === path);
  expect(calls.every((call) => call.authorization === `Bearer ${MOCK_ACCESS_TOKEN}`)).toBe(true);
}
