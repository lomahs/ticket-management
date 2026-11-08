/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react, { reactCompilerPreset } from '@vitejs/plugin-react';
import babel from '@rolldown/plugin-babel';

export default defineConfig({
  // React Compiler memoizes components and values, so a filter or a keystroke re-renders only the rows it changes.
  // Tests are not components: they skip it, which keeps Vitest's transform fast.
  plugins: [react(), babel({ presets: [reactCompilerPreset()], exclude: [/[\\/]node_modules[\\/]/, /\.test\.tsx?$/, /[\\/]src[\\/]test[\\/]/] })],
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8000' },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    restoreMocks: true,
    testTimeout: 15_000,
  },
});
