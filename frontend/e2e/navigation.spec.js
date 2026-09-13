// Browser regressions for responsive navigation and keyboard handoffs.
// Authentication and API data use the existing intercepted HTTP fixtures.
import { test, expect, signInThroughMockedContract } from './fixtures/mocked-contract.js';

test('mobile drawer contains keyboard focus and restores its opener on Escape', async ({ page, mockedContract }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await signInThroughMockedContract(page);

  const toggle = page.locator('[aria-controls="workspace-navigation"]');
  const navigation = page.getByRole('complementary', { name: 'Workspace navigation' });
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await toggle.click();

  const close = navigation.getByRole('button', { name: 'Close navigation' });
  await expect(navigation).toBeVisible();
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('main')).toHaveJSProperty('inert', true);
  await expect(close).toBeFocused();
  await page.screenshot({ path: testInfo.outputPath('drawer-open.png'), animations: 'disabled' });

  await page.keyboard.press('Shift+Tab');
  await expect(navigation.getByRole('button', { name: 'Sign out' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(close).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(navigation.getByRole('button', { name: 'Search workspace commands' })).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(navigation).toBeHidden();
  await expect(page.locator('main')).toHaveJSProperty('inert', false);
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(toggle).toBeFocused();
});

for (const shortcut of ['Control+k', 'Meta+k']) {
  test(`${shortcut} hands an open mobile drawer to an interactive command palette`, async ({ page, mockedContract }) => {
    await page.setViewportSize({ width: 390, height: 900 });
    await signInThroughMockedContract(page);
    const toggle = page.locator('[aria-controls="workspace-navigation"]');
    await toggle.click();
    await expect(page.getByRole('button', { name: 'Close navigation' })).toBeFocused();

    await page.keyboard.press(shortcut);
    const palette = page.getByRole('dialog', { name: 'Command palette' });
    const search = palette.getByRole('textbox', { name: 'Command search' });
    await expect(palette).toBeVisible();
    await expect(page.locator('main')).toHaveJSProperty('inert', false);
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await expect(search).toBeFocused();

    // Type through the keyboard so the test cannot conceal a failed focus handoff.
    await page.keyboard.type('Go to Knowledge Base');
    await expect(search).toHaveValue('Go to Knowledge Base');
    await expect(palette.getByRole('option')).toHaveCount(1);
    await page.keyboard.press('Enter');
    await expect(palette).toHaveCount(0);
    await expect(page).toHaveURL(/\/kb$/);
    await expect(page.getByRole('heading', { name: 'Knowledge Base', exact: true })).toBeVisible();
  });
}

test('resizing an open mobile drawer to desktop releases the workspace', async ({ page, mockedContract }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await signInThroughMockedContract(page);
  const toggle = page.locator('[aria-controls="workspace-navigation"]');
  await toggle.click();
  await expect(page.locator('main')).toHaveJSProperty('inert', true);

  await page.setViewportSize({ width: 1440, height: 900 });
  await expect(page.locator('main')).toHaveJSProperty('inert', false);
  await expect(toggle).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByRole('button', { name: 'Dismiss navigation' })).toHaveCount(0);
  await expect(toggle).toBeFocused();

  // A normal workspace action must work after the responsive transition.
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'System Settings' })).toBeVisible();
  await page.getByRole('button', { name: 'Close settings' }).click();

  await page.setViewportSize({ width: 390, height: 900 });
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect(page.getByRole('complementary', { name: 'Workspace navigation' })).toBeHidden();
  await expect(page.locator('main')).toHaveJSProperty('inert', false);
});

test('collapsed desktop navigation retains accessible names and usable destinations', async ({ page, mockedContract }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await signInThroughMockedContract(page);
  const toggle = page.getByRole('button', { name: 'Toggle sidebar' });
  const navigation = page.getByRole('complementary', { name: 'Workspace navigation' });
  const expandedWidth = (await navigation.boundingBox()).width;

  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await expect.poll(async () => (await navigation.boundingBox()).width).toBeLessThan(expandedWidth);
  for (const name of ['Assistant', 'Knowledge Base', 'Ingestion', 'Watchlists']) {
    await expect(navigation.getByRole('button', { name, exact: true })).toBeVisible();
  }
  await expect(navigation.getByRole('button', { name: 'Search workspace commands' })).toBeVisible();

  await navigation.getByRole('button', { name: 'Knowledge Base', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Knowledge Base', exact: true })).toBeVisible();
  await expect(navigation.getByRole('button', { name: 'Knowledge Base', exact: true })).toHaveAttribute('aria-current', 'page');
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
});
