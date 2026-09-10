import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// https://vitest.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    // The auth/401 redirect behavior is implemented in terms of
    // window.location.assign. jsdom provides a working
    // implementation, but we want to assert on it from each
    // test, so we expose a small helper through setupFiles.
    setupFiles: ['./src/test/setup.ts'],
  },
});
