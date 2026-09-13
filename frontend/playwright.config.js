// playwright.config.js — E2E smoke against the production frontend build.
// `npm run test:e2e` builds the app, serves it via `vite preview`, and runs
// the specs in e2e/. The API is not required: the smoke test asserts the
// unauthenticated shell (login gate) renders.

import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // The credential-bearing real stack uses playwright.real.config.js. Never
  // include it here, even if a caller leaks real-stack environment markers.
  testIgnore: ['**/real-stack.spec.js'],
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run preview',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
