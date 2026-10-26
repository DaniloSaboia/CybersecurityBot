// Render checks use intercepted HTTP fixtures, not a live backend.
import { test, expect, signInThroughMockedContract } from './fixtures/mocked-contract.js';

for (const width of [1440, 768, 390]) {
  test(`interface at ${width}px`, async ({ page, mockedContract }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Enter the Console' })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('login.png') });
    await signInThroughMockedContract(page);
    await expect(page.getByRole('button', { name: 'Settings', exact: true })).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Commands', exact: true })).toHaveCount(1);
    await expect(page.locator('.topbar-model-chip')).toHaveCount(0);
    await expect(page.locator('.rail-nav button')).toHaveCount(4);
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'System Settings' })).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('settings.png'), animations: 'disabled' });
    await page.getByRole('button', { name: 'Retrieval', exact: true }).click();
    await expect(page.getByRole('slider', { name: 'Retrieved passages' })).toBeVisible();
    await page.getByRole('slider', { name: 'Retrieved passages' }).fill('8');
    await page.screenshot({ path: testInfo.outputPath('retrieval.png'), animations: 'disabled' });
    await page.getByRole('button', { name: 'Connection', exact: true }).click();
    await page.screenshot({ path: testInfo.outputPath('connection.png'), animations: 'disabled' });
    await page.getByRole('button', { name: 'Save changes', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'System Settings' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Settings', exact: true }).click();
    await page.getByRole('button', { name: 'Retrieval', exact: true }).click();
    await expect(page.getByRole('slider', { name: 'Retrieved passages' })).toHaveValue('8');
    await page.getByRole('button', { name: 'Close settings' }).click();
    await page.getByRole('button', { name: 'Commands', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Command palette' })).toBeVisible();
    await page.keyboard.press('Escape');
    await page.keyboard.press('Control+k');
    await expect(page.getByRole('dialog', { name: 'Command palette' })).toBeVisible();
    await page.keyboard.press('Escape');
    for (const [route, heading] of [['/', 'AI Assistant'], ['/kb', 'Knowledge Base'], ['/ingest', 'Ingestion Pipeline'], ['/alerts', 'Watchlists & Alerts']]) {
      await page.goto(route);
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
      await page.screenshot({ path: testInfo.outputPath(`${route.replaceAll('/', '') || 'assistant'}-dark.png`), animations: 'disabled' });
      const overflowing = await page.evaluate(() => [...document.querySelectorAll('body *')].filter(el => el.getBoundingClientRect().right > innerWidth + 1 && getComputedStyle(el).position !== 'fixed').map(el => ({ tag: el.tagName, class: el.className, right: el.getBoundingClientRect().right })).slice(0, 12));
      expect.soft(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), JSON.stringify({ route, overflowing })).toBe(true);
      await page.evaluate(() => document.documentElement.dataset.theme = 'light');
      await page.screenshot({ path: testInfo.outputPath(`${route.replaceAll('/', '') || 'assistant'}-light.png`), animations: 'disabled' });
    }
  });
}
