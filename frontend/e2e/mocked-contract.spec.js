// MOCKED HTTP-CONTRACT E2E ONLY.
// These tests exercise the production frontend bundle against deterministic
// intercepted GoTrue/API responses. They do not prove backend JWT validation,
// RBAC/RLS, cross-user isolation, durable persistence, or real SSE transport.

import { Buffer } from 'node:buffer';
import {
  expect,
  expectMockedBearerRequest,
  signInThroughMockedContract,
  test,
} from './fixtures/mocked-contract.js';

function rail(page) {
  return page.locator('.rail-nav');
}

test.describe('mocked HTTP-contract E2Es (frontend behavior, not backend security proof)', () => {
  test('uploads, reaches indexed state, streams a cited answer, and reloads its saved conversation', async ({ page, mockedContract }) => {
    await signInThroughMockedContract(page);
    await expectMockedBearerRequest(mockedContract, '/me');

    await rail(page).getByRole('button', { name: 'Ingestion' }).click();
    await expect(page.getByRole('heading', { name: 'Ingestion Pipeline' })).toBeVisible();
    await page.getByLabel('Select one specification document').setInputFiles({
      name: 'security-brief.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('fixture security brief'),
    });
    await expect(page.getByText('Upload accepted and queued for ingestion.')).toBeVisible();
    await expect(page.getByText('security-brief.pdf')).toBeVisible();
    expect(mockedContract.uploads).toHaveLength(1);
    expect(mockedContract.uploads[0].contentType).toContain('multipart/form-data');
    expect(mockedContract.uploads[0].postData).toContain('security-brief.pdf');

    await rail(page).getByRole('button', { name: 'Assistant' }).click();
    await page.getByRole('textbox', { name: 'Technical query' }).fill('What is the patch sequence?');
    await page.getByRole('button', { name: 'Send technical query' }).click();
    await expect(page.getByText('Apply the vendor mitigation.')).toBeVisible();
    const citation = page.locator('.source-chip').filter({ hasText: 'CVE-2025-1111' });
    await expect(citation).toBeVisible();
    const exactEvidence = page.getByRole('button', { name: /view exact evidence for CVE-2025-1111, revision 7/i });
    await exactEvidence.click();
    const evidenceDialog = page.getByRole('dialog', { name: 'Exact evidence' });
    await expect(evidenceDialog).toBeVisible();
    await expect(evidenceDialog.getByTestId('exact-evidence-passage')).toHaveText('Apply the immutable vendor mitigation.');
    await expect(evidenceDialog.getByRole('link', { name: 'Open canonical source' })).toHaveAttribute('href', 'https://evidence.example/advisories/CVE-2025-1111');
    expect(mockedContract.evidenceRequests).toEqual([{
      keys: ['articleId', 'articleRevisionId', 'revisionNumber', 'evidenceSnapshotId', 'sourceSpanStart', 'sourceSpanEnd'],
      received: {
        articleId: 'CVE-2025-1111',
        articleRevisionId: 'article-revision-fixture-7',
        revisionNumber: '7',
        evidenceSnapshotId: 'evidence-snapshot-fixture-7',
        sourceSpanStart: '0',
        sourceSpanEnd: '37',
      },
    }]);
    await evidenceDialog.getByRole('button', { name: 'Close exact evidence' }).click();
    await citation.click();
    await expect(page).toHaveURL(/\/kb/);
    await expect(page.getByRole('heading', { name: 'Fixture Credential Exposure Advisory' })).toBeVisible();

    await rail(page).getByRole('button', { name: 'Assistant' }).click();
    const savedThread = page.locator('#saved-thread-panel .conv-open');
    await expect(savedThread).toContainText('What is the patch sequence?');
    await savedThread.click();
    await expect(page.getByText('Apply the vendor mitigation.')).toBeVisible();
    expect(mockedContract.conversationMessageRequests).toContain('conversation-fixture-1');

    // Citation references must persist with the saved assistant turn, not
    // merely the streamed in-memory message.
    await page.getByRole('button', { name: /view exact evidence for CVE-2025-1111, revision 7/i }).click();
    const restoredEvidence = page.getByRole('dialog', { name: 'Exact evidence' });
    await expect(restoredEvidence.getByTestId('exact-evidence-passage')).toHaveText('Apply the immutable vendor mitigation.');
    expect(mockedContract.evidenceRequests).toHaveLength(2);
  });

  test('keeps a mocked revoked exact-evidence response non-disclosing', async ({ page, mockedContract }) => {
    await signInThroughMockedContract(page);
    await page.getByRole('textbox', { name: 'Technical query' }).fill('Show the immutable fixture proof');
    await page.getByRole('button', { name: 'Send technical query' }).click();
    await expect(page.getByText('Apply the vendor mitigation.')).toBeVisible();
    mockedContract.fail('GET', '/kb/evidence/view', 404, 'fixture reference was revoked');

    await page.getByRole('button', { name: /view exact evidence for CVE-2025-1111, revision 7/i }).click();
    const evidenceDialog = page.getByRole('dialog', { name: 'Exact evidence' });
    await expect(evidenceDialog.getByText('Evidence record unavailable')).toBeVisible();
    await expect(evidenceDialog.getByTestId('exact-evidence-passage')).toHaveCount(0);
    await expect(evidenceDialog.getByRole('link', { name: 'Open canonical source' })).toHaveCount(0);
    await expect(evidenceDialog.getByText('fixture reference was revoked')).toHaveCount(0);
    await expect(page).not.toHaveURL(/\/kb/);
  });

  test('shows a failed job being retried to indexed state', async ({ page, mockedContract }) => {
    await signInThroughMockedContract(page);
    await rail(page).getByRole('button', { name: 'Ingestion' }).click();

    const failedJob = page.getByLabel('failed-ingest.pdf: FAILED');
    await expect(failedJob).toBeVisible();
    await failedJob.getByRole('button', { name: 'RETRY' }).click();
    await expect(page.getByText('Ingestion job returned to the execution queue.')).toBeVisible();
    await expect(page.getByLabel('failed-ingest.pdf: INDEXED')).toBeVisible();
    expect(mockedContract.retries).toHaveLength(1);
  });

  test('creates a watchlist and marks its mocked alert feed read', async ({ page, mockedContract }) => {
    await signInThroughMockedContract(page);
    await rail(page).getByRole('button', { name: 'Watchlists' }).click();
    await expect(page.getByRole('heading', { name: 'Watchlists & Alerts' })).toBeVisible();

    await page.getByLabel('Watchlist label').fill('Critical edge CVEs');
    await page.getByLabel('Watch terms').fill('CVE-2025-1111, edge');
    await page.getByLabel('Minimum severity').selectOption('high');
    await page.getByRole('button', { name: /CREATE/ }).click();
    await expect(page.getByText('Critical edge CVEs')).toBeVisible();
    expect(mockedContract.watchlistCreates).toHaveLength(1);
    expect(mockedContract.watchlistCreates[0]).toMatchObject({
      terms: ['CVE-2025-1111', 'edge'],
      minSeverity: 'high',
    });

    await page.getByRole('button', { name: /MARK ALL/ }).click();
    await expect(page.getByText(/0 UNREAD · 1 WATCHLISTS/)).toBeVisible();
    expect(mockedContract.readAllCalls).toBe(1);
  });

  test('returns a 401 to the session-expired login state', async ({ page, mockedContract }) => {
    await signInThroughMockedContract(page);
    mockedContract.fail('GET', '/ingest/jobs', 401, 'expired bearer token');
    await rail(page).getByRole('button', { name: 'Ingestion' }).click();

    await expect(page.getByRole('heading', { name: 'Enter the Console' })).toBeVisible();
    await expect(page.getByRole('status')).toContainText('Session expired — sign in again to continue.');
    await expect.poll(() => mockedContract.authRequests.filter((request) => request.path.endsWith('/auth/v1/logout')).length).toBe(1);
  });

  test('shows a 403 permission denial without signing out the valid mocked user', async ({ page, mockedContract }) => {
    await signInThroughMockedContract(page);
    await rail(page).getByRole('button', { name: 'Ingestion' }).click();
    await expect(page.getByRole('heading', { name: 'Ingestion Pipeline' })).toBeVisible();
    mockedContract.fail('POST', '/connectors/nvd/sync', 403, 'admin role required');

    await page.getByTitle('Refresh NVD CVE Feed').click();
    await expect(page.getByRole('alert')).toContainText('Permission denied. Your account does not have access to this resource.');
    await expect(page.getByRole('heading', { name: 'Ingestion Pipeline' })).toBeVisible();
    expect(mockedContract.authRequests.filter((request) => request.path.endsWith('/auth/v1/logout'))).toHaveLength(0);
  });
});
