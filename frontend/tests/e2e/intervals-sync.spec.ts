import { expect, test } from '@playwright/test'

const connectedStatus = {
  configured: true,
  running: false,
  lastSuccessfulSyncAt: null,
  lastAttemptAt: null,
  retryAfterUntil: null,
  lastResult: null,
  message: null,
}

test('shows the Intervals card and the result of a manual sync', async ({ page }) => {
  let syncRequests = 0
  await page.route('**/api/imports/intervals-icu/status', (route) => route.fulfill({ json: connectedStatus }))
  await page.route('**/api/imports/intervals-icu/sync', async (route) => {
    syncRequests++
    expect(route.request().method()).toBe('POST')
    await route.fulfill({
      status: 202,
      json: {
        ...connectedStatus,
        lastSuccessfulSyncAt: '2026-10-19T10:43:00Z',
        lastResult: {
          started: true,
          configured: true,
          checked: 3,
          imported: 1,
          alreadyPresent: 2,
          skipped: 0,
          failed: 0,
          activities: [],
          error: null,
          attemptedAt: '2026-10-19T10:42:00Z',
          completedAt: '2026-10-19T10:43:00Z',
        },
      },
    })
  })

  await page.goto('/import')
  await expect(page.getByTestId('intervals-status')).toContainText('Verbunden')
  await page.getByTestId('intervals-sync').click()

  await expect(page.getByTestId('intervals-result')).toContainText('3 Aktivitäten geprüft')
  await expect(page.getByTestId('intervals-result')).toContainText('1 neuer Lauf importiert')
  await expect(page.getByTestId('intervals-result')).toContainText('2 bereits vorhanden')
  expect(syncRequests).toBe(1)
})
