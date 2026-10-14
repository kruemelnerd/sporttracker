import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  workers: 1,
  use: {
    baseURL: 'http://127.0.0.1:15173',
  },
  globalSetup: './tests/e2e/global-setup.ts',
  webServer: [
    {
      command: 'cd ../backend && DATABASE_URL=jdbc:postgresql://127.0.0.1:55432/running_dashboard_e2e DATABASE_USERNAME=running_dashboard_e2e DATABASE_PASSWORD=running_dashboard_e2e SERVER_PORT=18080 DATA_DIRECTORY=/tmp/running-dashboard-m4-e2e ./gradlew bootRun',
      url: 'http://127.0.0.1:18080/api/health',
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: 'VITE_API_PROXY_TARGET=http://127.0.0.1:18080 npm run dev -- --host 127.0.0.1 --port 15173 --strictPort',
      url: 'http://127.0.0.1:15173/',
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
  reporter: 'line',
})
