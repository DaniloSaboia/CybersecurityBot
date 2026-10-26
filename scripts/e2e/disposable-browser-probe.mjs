// Read-only browser-auth preflight diagnostics; no interception or disk artifacts.
import { createRequire } from 'node:module';
import { loginAs } from '../../frontend/e2e/real-stack-auth.js';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
export default async function preflight(config) {
  const browser = await chromium.launch();
  const context = await browser.newContext({baseURL: config.projects[0].use.baseURL});
  const page = await context.newPage();
  const observations = [];
  page.on('console', message => { if (message.type() === 'error') observations.push(message.text()); });
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.pathname.includes('/auth/') || url.pathname.startsWith('/api/')) observations.push(`${response.request().method()} ${url.pathname} ${response.status()}`);
  });
  page.on('requestfailed', request => observations.push(`REQUEST_FAILED ${new URL(request.url()).pathname} ${request.failure()?.errorText}`));
  try {
    await loginAs(page, 'primary');
    console.log('Browser GoTrue password-login preflight passed.');
  } catch {
    // Parent runner redacts every generated credential/JWT before writing output.
    console.log('Browser auth diagnostics:', observations.join('; '));
    console.log('Browser visible state:', (await page.locator('body').innerText()).slice(0, 2500));
    throw new Error('Browser GoTrue password-login preflight failed');
  } finally { await context.close(); await browser.close(); }
}
