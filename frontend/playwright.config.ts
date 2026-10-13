import { defineConfig } from '@playwright/test';

/**
 * End-to-end tests against a demo server of backend-java (simulated Jira, fixed "today", fresh data per run).
 * Needs a JDK 21 (java on PATH or JAVA_HOME).
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
    command: `npm run build && cd ../backend-java && ./mvnw -q package -Dmaven.test.skip=true && SPRING_PROFILES_ACTIVE=demo PORT=${PORT} \${JAVA_HOME:+\$JAVA_HOME/bin/}java -jar target/tracker.jar`,
    url: `http://localhost:${PORT}/api/v1/meta`,
    reuseExistingServer: false,
    timeout: 240_000,
  },
});
