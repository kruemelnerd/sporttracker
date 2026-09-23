import { expect, test } from '@playwright/test'

test('renders the real fixture summary through the Vite proxy', async ({ page, request }) => {
  const response = await request.get('/api/fixture-summary')
  expect(response.ok()).toBeTruthy()
  const summary = await response.json()

  expect(summary.sport).toBe('running')
  expect(summary.status).toBe('decoded')
  expect(summary.recordCount).toBeGreaterThan(0)
  expect(summary.lapCount).toBeGreaterThan(0)

  await page.goto('/')
  await expect(page.getByTestId('fixture-summary')).toBeVisible()
  await expect(page.getByTestId('sport')).toHaveText(summary.sport)
  await expect(page.getByTestId('status')).toHaveText(summary.status)
  await expect(page.getByTestId('record-count')).toHaveText(String(summary.recordCount))
  await expect(page.getByTestId('lap-count')).toHaveText(String(summary.lapCount))
  await expect(page.getByTestId('distance')).toHaveText(`${(summary.distanceMeters / 1000).toFixed(2)} km`)
})
