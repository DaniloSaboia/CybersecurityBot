import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In Docker the backend is reachable as http://backend:8080; locally it's
// http://localhost:8080. The dev server proxies /api -> backend so the app
// always calls a same-origin /api and no CORS is needed in dev.
const target = process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
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
