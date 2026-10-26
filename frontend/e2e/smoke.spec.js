// e2e/smoke.spec.js — load-bearing path: the SPA builds, boots and renders
// the auth gate without a backend. If this fails, every other journey fails.

import { test, expect } from '@playwright/test';

test.describe('Sentinel KMS smoke', () => {
  test('boots to the login gate', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/Sentinel/i);

    // The login surface renders its console framing.
    await expect(page.locator('body')).toBeVisible();
    const hasAuth =
      (await page.getByText(/sign in|login|access the console/i).count()) > 0
      || (await page.locator('input[type="email"], input[type="password"]').count()) > 0;
    expect(hasAuth).toBeTruthy();
  });

  test('serves the app bundle with styles applied', async ({ page }) => {
    await page.goto('/');
    // The Red Noir system sets a CSS variable on :root — proof CSS shipped.
    const bg = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--accent').trim());
    expect(bg).not.toBe('');
  });
});
