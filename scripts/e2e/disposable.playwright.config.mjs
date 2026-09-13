// Wrapper lives outside frontend; preserves its real-suite artifact/auth policy.
import inherited from '../../frontend/playwright.real.config.js';
import { fileURLToPath } from 'node:url';
const port = Number(process.env.SENTINEL_E2E_UI_PORT);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('SENTINEL_E2E_UI_PORT must be a nonprivileged local port');
const url = `http://127.0.0.1:${port}`;
export default {
  ...inherited,
  globalSetup: fileURLToPath(new URL('./disposable-browser-probe.mjs', import.meta.url)),
  testDir: fileURLToPath(new URL('../../frontend/e2e', import.meta.url)),
  outputDir: fileURLToPath(new URL('../../frontend/test-results', import.meta.url)),
  use: {...inherited.use, baseURL: url, trace: 'off', screenshot: 'off', video: 'off'},
  reporter: 'list', retries: 0,
  webServer: {...inherited.webServer, cwd: fileURLToPath(new URL('../../frontend', import.meta.url)),
    command: `node node_modules/vite/bin/vite.js preview --host 127.0.0.1 --port ${port} --strictPort`, url, reuseExistingServer: false},
};
