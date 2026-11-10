import { expect, test } from '@playwright/test'

const status = {
  enabled: true,
  apiKeyPresent: true,
  configured: true,
  connected: true,
  running: false,
  automaticSyncSuppressed: false,
  lastAttemptAt: null,
  lastSuccessfulSyncAt: null,
  retryAfterUntil: null,
  lastResult: null,
}

test('shows Garmin FIT sync status and starts manual synchronization', async ({ page }) => {
  let requests = 0
  await page.route('**/api/imports/garmin/status', (route) => route.fulfill({ json: status }))
  await page.route('**/api/imports/garmin/sync', async (route) => {
    requests++
    expect(route.request().method()).toBe('POST')
    await route.fulfill({ status: 202, json: {
      ...status,
      lastSuccessfulSyncAt: '2026-10-19T10:43:00Z',
      lastResult: {
        started: true, checked: 2, imported: 1, alreadyPresent: 1, skipped: 0, failed: 0,
        error: null, attemptedAt: '2026-10-19T10:42:00Z', completedAt: '2026-10-19T10:43:00Z',
      },
    } })
  })

  await page.goto('/import')
  await expect(page.getByTestId('garmin-status')).toContainText('Verbindung bestätigt')
  await page.getByTestId('garmin-sync').click()
  await expect(page.getByTestId('garmin-result')).toContainText('2 geprüft · 1 neu · 1 bereits vorhanden')
  await expect(page.getByTestId('garmin-card')).toContainText('19.10.2026')
  expect(requests).toBe(1)
  await expect(page.getByTestId('intervals-card')).toBeVisible()
})
