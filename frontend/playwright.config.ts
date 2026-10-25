import { defineConfig } from '@playwright/test'

const runId = process.env.TEST_RUN_ID ?? ''
const project = process.env.TEST_COMPOSE_PROJECT ?? ''
const database = process.env.TEST_DATABASE_NAME ?? ''
const username = process.env.TEST_DATABASE_USERNAME ?? ''
const password = process.env.TEST_DATABASE_PASSWORD ?? ''
const databasePort = process.env.TEST_DATABASE_PORT ?? ''
const databaseUrl = process.env.TEST_DATABASE_URL ?? ''
const dataDirectory = process.env.TEST_DATA_DIRECTORY ?? ''
const backendUrl = process.env.TEST_BACKEND_URL ?? ''
const frontendUrl = process.env.TEST_FRONTEND_URL ?? ''

if (!/^[a-f0-9]{24}$/.test(runId) || project !== `sporttracker-ci-${runId}` ||
  database !== `running_dashboard_test_${runId}` || username !== database || password.length !== 48 ||
  !/^[a-f0-9]{48}$/.test(password) || !/^[1-9][0-9]{0,4}$/.test(databasePort) || Number(databasePort) > 65535 ||
  databaseUrl !== `jdbc:postgresql://127.0.0.1:${databasePort}/${database}` ||
  dataDirectory !== `/tmp/running-dashboard-test-${runId}`) {
  throw new Error('Refusing Playwright run with a missing or inconsistent isolated test database identity')
}

function loopbackUrl(value: string): URL {
  const url = new URL(value)
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Playwright test server URLs must use an explicit 127.0.0.1 port')
  }
  return url
}

const backend = loopbackUrl(backendUrl)
const frontend = loopbackUrl(frontendUrl)

export default defineConfig({
  testDir: './tests/e2e',
  workers: 1,
  use: {
    baseURL: frontend.origin,
  },
  globalSetup: './tests/e2e/global-setup.ts',
  webServer: [
    {
      command: `cd ../backend && DATABASE_URL="$TEST_DATABASE_URL" DATABASE_USERNAME="$TEST_DATABASE_USERNAME" DATABASE_PASSWORD="$TEST_DATABASE_PASSWORD" SERVER_ADDRESS=127.0.0.1 SERVER_PORT=${backend.port} DATA_DIRECTORY="$TEST_DATA_DIRECTORY" INTERVALS_ICU_ENABLED=false INTERVALS_ICU_API_KEY= ./gradlew bootRun`,
      url: new URL('/api/health', backend).toString(),
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: `VITE_API_PROXY_TARGET="${backend.origin}" npm run dev -- --host 127.0.0.1 --port ${frontend.port} --strictPort`,
      url: frontend.origin,
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
  reporter: 'line',
})
