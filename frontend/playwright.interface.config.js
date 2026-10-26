import { defineConfig } from '@playwright/test';
import config from './playwright.config.js';

// Isolate fixture builds from developer servers and repository credentials.
export default defineConfig({
  ...config,
  use: { ...config.use, baseURL: 'http://localhost:49836' },
  webServer: {
    command: 'npm run build && npm run preview -- --port 49836',
    url: 'http://localhost:49836',
    reuseExistingServer: false,
    env: {
      SENTINEL_REAL_STACK_E2E: '1',
      VITE_SUPABASE_URL: 'http://localhost:54321',
      VITE_SUPABASE_ANON_KEY: 'interface-fixture-only',
    },
  },
});
