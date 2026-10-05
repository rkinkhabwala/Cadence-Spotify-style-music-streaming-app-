/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies the API, so the browser sees one origin (no CORS, and playback manifest URLs, built from the
// request's Host, point back through this proxy). CADENCE_API_URL overrides the API location.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const api = env.CADENCE_API_URL ?? 'http://localhost:8080';
  const proxy = { '/api': { target: api, xfwd: true }, '/.well-known': { target: api } };
  return {
    plugins: [react()],
    server: { port: 5173, strictPort: true, proxy },
    preview: { port: 4173, strictPort: true, proxy },
    build: { sourcemap: true, chunkSizeWarningLimit: 900 },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  };
});
