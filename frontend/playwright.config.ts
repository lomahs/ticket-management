import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests against a demo server (simulated Jira, fixed "today", fresh data per run).
 * Browser: PW_CHANNEL=chrome uses the installed Chrome; otherwise Playwright's own Chromium
 * (`npx playwright install chromium`).
 */
const PORT = 8100;

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  use: { baseURL: `http://localhost:${PORT}`, channel: process.env.PW_CHANNEL, trace: 'retain-on-failure' },
  webServer: {
    command: `npm run build && cd ../backend && TRACKER_MODE=demo .venv/bin/uvicorn tracker.bootstrap:create_app_from_env --factory --port ${PORT}`,
    url: `http://localhost:${PORT}/api/v1/meta`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
