// Dedicated config for credential-bearing disposable-stack browser coverage.
// It is never selected by the default smoke/mocked-contract command.

import { defineConfig, devices } from '@playwright/test';
import { assertRealStackBuildEnvironment } from './e2e/real-stack-contract.js';

assertRealStackBuildEnvironment();

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/real-stack.spec.js',
  timeout: 240_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  // Stateful upload/watchlist tests must never retry and duplicate fixtures.
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:5173',
    // Avoid credential-bearing browser artifacts until redaction is available.
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run preview',
    url: 'http://localhost:5173',
    // Do not accidentally test a stale preview with the wrong proxy/build.
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
