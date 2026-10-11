// Reproducible screenshots of the current main-worktree frontend.
// GoTrue and /api responses are intercepted deterministic fixtures: these
// images show interface states, not backend execution or human evaluation.
import { Buffer } from 'node:buffer';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { expect, signInThroughMockedContract, test } from './fixtures/mocked-contract.js';

const figureDir = process.env.THESIS_FIGURE_DIR;
if (!figureDir || !path.isAbsolute(figureDir)) {
  throw new Error('Set THESIS_FIGURE_DIR to an absolute output directory');
}

async function shot(page, filename, selector) {
  const target = selector ? page.locator(selector) : page;
  await target.screenshot({ path: path.join(figureDir, filename), animations: 'disabled' });
}

test('current main frontend: library, ingestion, assistant, and exact evidence', async ({ page, mockedContract }) => {
  await mkdir(figureDir, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInThroughMockedContract(page);
  await expect(page.getByRole('heading', { name: 'AI Assistant' })).toBeVisible();
  await shot(page, 'main-assistant-start-mocked.png');

  await page.locator('.rail-nav').getByRole('button', { name: 'Knowledge Base' }).click();
  await expect(page.getByRole('heading', { name: 'Knowledge Base' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Fixture Credential Exposure Advisory' })).toBeVisible();
  await shot(page, 'main-knowledge-base-mocked.png');

  await page.locator('.rail-nav').getByRole('button', { name: 'Ingestion' }).click();
  await expect(page.getByRole('heading', { name: 'Ingestion Pipeline' })).toBeVisible();
  await page.getByLabel('Select one specification document').setInputFiles({
    name: 'security-brief.pdf', mimeType: 'application/pdf', buffer: Buffer.from('fixture security brief'),
  });
  await expect(page.getByText('Upload accepted and queued for ingestion.')).toBeVisible();
  await expect(page.getByText('security-brief.pdf')).toBeVisible();
  await shot(page, 'main-ingestion-mocked.png');

  await page.locator('.rail-nav').getByRole('button', { name: 'Assistant' }).click();
  await page.getByRole('textbox', { name: 'Technical query' }).fill('What is the patch sequence?');
  await page.getByRole('button', { name: 'Send technical query' }).click();
  await expect(page.getByText('Apply the vendor mitigation.')).toBeVisible();
  await expect(page.locator('.source-chip').filter({ hasText: 'CVE-2025-1111' })).toBeVisible();
  await shot(page, 'main-cited-answer-mocked.png');

  await page.getByRole('button', { name: /view exact evidence for CVE-2025-1111, revision 7/i }).click();
  const dialog = page.getByRole('dialog', { name: 'Exact evidence' });
  await expect(dialog.getByTestId('exact-evidence-passage')).toHaveText('Apply the immutable vendor mitigation.');
  await expect(dialog.getByRole('link', { name: 'Open canonical source' }))
    .toHaveAttribute('href', 'https://evidence.example/advisories/CVE-2025-1111');
  await shot(page, 'main-evidence-dialog-mocked.png');
  await dialog.screenshot({ path: path.join(figureDir, 'main-evidence-detail-mocked.png'), animations: 'disabled' });

  expect(mockedContract.uploads).toHaveLength(1);
  expect(mockedContract.evidenceRequests).toHaveLength(1);
});
