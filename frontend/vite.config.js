import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In Docker the backend is reachable as http://backend:8080; locally it's
// http://localhost:8080. The dev server proxies /api -> backend so the app
// always calls a same-origin /api and no CORS is needed in dev.
const target = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080';
const realStackE2E = process.env.SENTINEL_REAL_STACK_E2E === '1';

export default defineConfig({
  plugins: [react()],
  // Keep the unit suite in a browser-like, same-origin context. This gives
  // localStorage its DOM implementation under the supported Node 24 runtime;
  // it intentionally does not shim storage in application code.
  test: {
    // Browser journeys are owned by Playwright; never import its test globals
    // into Vitest just because a new major Vitest release changes CLI exclude
    // handling.
    include: ['src/**/*.{test,spec}.{js,jsx,ts,tsx}'],
    environment: 'jsdom',
    environmentOptions: {
      jsdom: { url: 'http://localhost/' },
    },
  },
  // The ordinary developer build can share repository-level non-browser
  // defaults. The credential-bearing disposable real-stack suite must use
  // only Shell-injected process variables: never load a repository .env
  // while that suite builds or launches its preview server.
  envDir: realStackE2E ? false : '..',
  server: {
    host: true,
    port: 5173,
    proxy: {
      '/api': { target, changeOrigin: true },
    },
  },
  preview: {
    host: true,
    port: 5173,
    proxy: {
      '/api': { target, changeOrigin: true },
    },
  },
});
