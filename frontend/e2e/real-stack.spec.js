// REAL-STACK BROWSER E2E ONLY.
//
// Shell supplies a disposable local backend, GoTrue, corpus fixture, and child
// environment. This suite contains no HTTP interception and is excluded from
// ordinary mocked-contract/smoke runs unless SENTINEL_REAL_STACK_E2E=1.
// The indexed upload and exact-evidence retrieval are deliberately separate:
// E2E_APPROVED_EVIDENCE_QUERY must match a Shell-provisioned current-approved
// article snapshot.
// Private/upload-only provenance must not be converted into an article link.

import { expect, test } from '@playwright/test';
import { assertDisposableCredentials, loginAs } from './real-stack-auth.js';
import {
  assertRealStackFixtures,
  isNonBlankEnvironmentValue,
  isRealStackEnabled,
} from './real-stack-contract.js';

const enabled = isRealStackEnabled();

if (enabled) {
  assertDisposableCredentials();
  assertRealStackFixtures();
}

const input = {
  uploadPath: process.env.E2E_UPLOAD_PATH,
  // This query must retrieve the independently seeded approved article below,
  // not the freshly indexed private upload.
  query: process.env.E2E_APPROVED_EVIDENCE_QUERY,
  answerFragment: process.env.E2E_EXPECTED_ANSWER_FRAGMENT,
  articleId: process.env.E2E_EXPECTED_ARTICLE_ID,
  articleTitle: process.env.E2E_EXPECTED_ARTICLE_TITLE,
  articleRevisionId: process.env.E2E_EXPECTED_ARTICLE_REVISION_ID,
  revisionNumber: process.env.E2E_EXPECTED_REVISION_NUMBER,
  evidenceSnapshotId: process.env.E2E_EXPECTED_EVIDENCE_SNAPSHOT_ID,
  sourceSpanStart: process.env.E2E_EXPECTED_SOURCE_SPAN_START,
  sourceSpanEnd: process.env.E2E_EXPECTED_SOURCE_SPAN_END,
  evidenceFragment: process.env.E2E_EXPECTED_EVIDENCE_FRAGMENT,
  sourceVersion: process.env.E2E_EXPECTED_SOURCE_VERSION,
  watchlistLabel: process.env.E2E_WATCHLIST_LABEL,
  expectedAlertFragment: process.env.E2E_EXPECTED_ALERT_FRAGMENT,
  failedJobName: process.env.E2E_FAILED_JOB_NAME,
};
const uploadName = input.uploadPath?.split(/[\\/]/).pop();

test.describe.configure({ mode: 'serial' });

test.describe('real local stack: normal GoTrue browser journeys', () => {
  test.skip(!enabled, 'Set SENTINEL_REAL_STACK_E2E=1 with Shell-provided disposable-stack inputs.');

  test('A uploads, indexes, retrieves via SSE, persists a conversation, and opens exact evidence', async ({ browser }) => {
    test.setTimeout(240_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await loginAs(page, 'primary');

      await rail(page).getByRole('button', { name: 'Ingestion' }).click();
      await expect(page.getByRole('heading', { name: 'Ingestion Pipeline' })).toBeVisible();
      const uploadResponse = page.waitForResponse((response) => (
        response.request().method() === 'POST'
        && new URL(response.url()).pathname.endsWith('/api/ingest/upload')
      ));
      await page.getByLabel('Select one specification document').setInputFiles(input.uploadPath);
      const uploaded = await uploadResponse;
      expect(uploaded.ok()).toBe(true);
      const uploadedJob = await uploaded.json();
      expect(uploadedJob).toMatchObject({ name: uploadName });
      expect(typeof uploadedJob.id).toBe('string');
      await expect(page.getByText('Upload accepted and queued for ingestion.')).toBeVisible();
      await expect(page.getByLabel(`${uploadName}: INDEXED`)).toBeVisible({ timeout: 120_000 });

      await rail(page).getByRole('button', { name: 'Assistant' }).click();
      const streamResponse = page.waitForResponse((response) => (
        response.request().method() === 'POST'
        && new URL(response.url()).pathname.endsWith('/api/assistant/chat/stream')
      ));
      await page.getByRole('textbox', { name: 'Technical query' }).fill(input.query);
      await page.getByRole('button', { name: 'Send technical query' }).click();
      const response = await streamResponse;
      expect(response.ok()).toBe(true);
      expect(response.headers()['content-type'] || '').toContain('text/event-stream');
      await expect(page.getByText(input.answerFragment)).toBeVisible({ timeout: 60_000 });

      const legacyCitation = page.locator('.source-chip').filter({ hasText: input.articleId });
      await expect(legacyCitation).toBeVisible();
      const evidenceButton = page.locator('.evidence-reference').filter({ hasText: input.articleId }).first();
      await expect(evidenceButton).toBeVisible();
      const evidenceResponse = page.waitForResponse((nextResponse) => (
        nextResponse.request().method() === 'GET'
        && new URL(nextResponse.url()).pathname.endsWith('/api/kb/evidence/view')
      ));
      await evidenceButton.click();
      const openedEvidence = await evidenceResponse;
      expect(openedEvidence.status()).toBe(200);
      const evidenceQuery = new URL(openedEvidence.url()).searchParams;
      expect([...evidenceQuery.keys()]).toEqual([
        'articleId',
        'articleRevisionId',
        'revisionNumber',
        'evidenceSnapshotId',
        'sourceSpanStart',
        'sourceSpanEnd',
      ]);
      expect(evidenceQuery.has('sourceUri')).toBe(false);
      expect(evidenceQuery.has('sourceVersion')).toBe(false);
      expect(evidenceQuery.has('revisionStatus')).toBe(false);
      expect(Object.fromEntries(evidenceQuery.entries())).toEqual({
        articleId: input.articleId,
        articleRevisionId: input.articleRevisionId,
        revisionNumber: input.revisionNumber,
        evidenceSnapshotId: input.evidenceSnapshotId,
        sourceSpanStart: input.sourceSpanStart,
        sourceSpanEnd: input.sourceSpanEnd,
      });
      const evidenceDialog = page.getByRole('dialog', { name: 'Exact evidence' });
      await expect(evidenceDialog).toBeVisible();
      await expect(evidenceDialog.getByTestId('exact-evidence-passage')).toContainText(input.evidenceFragment);
      await expect(evidenceDialog.getByText(input.sourceVersion)).toBeVisible();
      await evidenceDialog.getByRole('button', { name: 'Close exact evidence' }).click();

      // Legacy citations remain mutable article navigation by design; exact
      // evidence is opened only through the separate snapshot control above.
      await legacyCitation.click();
      await expect(page).toHaveURL(/\/kb/);
      await expect(page.getByRole('heading', { name: input.articleTitle })).toBeVisible();

      await rail(page).getByRole('button', { name: 'Assistant' }).click();
      await page.reload();
      await expect(page.getByRole('heading', { name: 'AI Assistant' })).toBeVisible();
      const savedThread = page.locator('#saved-thread-panel .conv-open').filter({ hasText: input.query }).first();
      await expect(savedThread).toBeVisible({ timeout: 30_000 });
      await savedThread.click();
      await expect(page.getByText(input.answerFragment)).toBeVisible();
      const persistedEvidence = page.locator('.evidence-reference').filter({ hasText: input.articleId }).first();
      await expect(persistedEvidence).toBeVisible();
      const persistedEvidenceResponse = page.waitForResponse((nextResponse) => (
        nextResponse.request().method() === 'GET'
        && new URL(nextResponse.url()).pathname.endsWith('/api/kb/evidence/view')
      ));
      await persistedEvidence.click();
      expect((await persistedEvidenceResponse).ok()).toBe(true);
      const persistedDialog = page.getByRole('dialog', { name: 'Exact evidence' });
      await expect(persistedDialog.getByTestId('exact-evidence-passage')).toContainText(input.evidenceFragment);
      await persistedDialog.getByRole('button', { name: 'Close exact evidence' }).click();

      await rail(page).getByRole('button', { name: 'Watchlists' }).click();
      await page.getByLabel('Watchlist label').fill(input.watchlistLabel);
      await page.getByLabel('Watch terms').fill(input.articleId);
      const watchlistResponse = page.waitForResponse((response) => (
        response.request().method() === 'POST'
        && new URL(response.url()).pathname.endsWith('/api/watchlists')
      ));
      await page.getByRole('button', { name: /CREATE WATCHLIST/i }).click();
      const createdWatchlist = await watchlistResponse;
      expect(createdWatchlist.ok()).toBe(true);
      expect(await createdWatchlist.json()).toMatchObject({ label: input.watchlistLabel });
      await expect(page.getByText(input.watchlistLabel)).toBeVisible();
      await page.reload();
      await expect(page.getByText(input.watchlistLabel)).toBeVisible();

      // Alert generation requires a Shell-provisioned matching fixture. The
      // unconditional assertions above still prove watchlist persistence.
      if (isNonBlankEnvironmentValue(input.expectedAlertFragment)) {
        await expect(page.getByRole('feed', { name: 'Watchlist alerts' })).toContainText(input.expectedAlertFragment, { timeout: 75_000 });
      }
    } finally {
      await context.close();
    }
  });

  test('B cannot discover A-owned saved conversation or watchlist labels in the normal UI', async ({ browser }) => {
    // This is browser/UI isolation only. It does not claim direct evidence
    // endpoint authorization; Shell's backend security suite owns that proof.
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      const conversationsResponse = page.waitForResponse((response) => (
        response.request().method() === 'GET'
        && new URL(response.url()).pathname.endsWith('/api/assistant/conversations')
      ));
      await loginAs(page, 'other');
      expect((await conversationsResponse).ok()).toBe(true);
      await expect(page.locator('#saved-thread-panel .conv-open').filter({ hasText: input.query })).toHaveCount(0);

      const watchlistsResponse = page.waitForResponse((response) => (
        response.request().method() === 'GET'
        && new URL(response.url()).pathname.endsWith('/api/watchlists')
      ));
      await rail(page).getByRole('button', { name: 'Watchlists' }).click();
      expect((await watchlistsResponse).ok()).toBe(true);
      await expect(page.getByText(input.watchlistLabel)).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  test('retries a Shell-provisioned inactive failed upload only when one is supplied', async ({ browser }) => {
    test.skip(!isNonBlankEnvironmentValue(input.failedJobName), 'Shell did not provide a deterministic inactive failed-job fixture.');
    test.setTimeout(150_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    try {
      await loginAs(page, 'primary');
      await rail(page).getByRole('button', { name: 'Ingestion' }).click();
      const failedJob = page.getByLabel(`${input.failedJobName}: FAILED`);
      await expect(failedJob).toBeVisible({ timeout: 30_000 });
      const retryResponse = page.waitForResponse((response) => (
        response.request().method() === 'POST'
        && /\/api\/ingest\/jobs\/[^/]+\/retry$/.test(new URL(response.url()).pathname)
      ));
      await failedJob.getByRole('button', { name: 'RETRY' }).click();
      expect((await retryResponse).ok()).toBe(true);
      await expect(page.getByText('Ingestion job returned to the execution queue.')).toBeVisible();
      await expect(page.getByLabel(`${input.failedJobName}: INDEXED`)).toBeVisible({ timeout: 90_000 });
    } finally {
      await context.close();
    }
  });
});

function rail(page) {
  return page.locator('.rail-nav');
}
