import { expect, test, type Page } from '@playwright/test'

const activity = (id: number, daysAgo: number, distanceMeters: number, elapsedSeconds: number, extras: Record<string, unknown> = {}) => ({
  id,
  sport: 'running',
  subSport: extras.subSport ?? null,
  startTime: new Date(Date.now() - daysAgo * 24 * 60 * 60 * 1000).toISOString(),
  elapsedSeconds,
  distanceMeters,
  averageHeartRate: extras.averageHeartRate ?? null,
  ascentMeters: extras.ascentMeters ?? null,
})

async function openDashboard(page: Page, data: object[]) {
  await page.route('**/api/activities?*', (route) => route.fulfill({ json: data }))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Letzte sechs Wochen' })).toBeVisible()
}

test('dashboard metrics are calculated from activity values', async ({ page }) => {
  await openDashboard(page, [
    activity(10, 1, 10000, 3600, { averageHeartRate: 150, ascentMeters: 200, subSport: 'trail' }),
    activity(11, 10, 5000, 1800),
  ])

  await expect(page.getByTestId('run-count')).toHaveText('2')
  await expect(page.getByTestId('total-distance')).toHaveText('15.00 km')
  await expect(page.getByTestId('total-time')).toHaveText('1 h 30 min')
  await expect(page.getByTestId('average-distance')).toHaveText('7.50 km')
  await expect(page.getByTestId('average-pace')).toHaveText('6:00 min/km')
  await expect(page.getByTestId('total-ascent')).toHaveText('200 m')
})

test('run list shows concrete values including pace and optional heart rate', async ({ page }) => {
  const run = activity(12, 1, 10000, 3600, { averageHeartRate: 150, subSport: 'trail' })
  await openDashboard(page, [run])

  const row = page.getByTestId('activity-12')
  await expect(row.locator('time')).toHaveAttribute('datetime', run.startTime)
  await expect(row).toContainText('trail')
  await expect(row).toContainText('10.00 km')
  await expect(row).toContainText('1 h 00 min')
  await expect(row).toContainText('6:00 min/km')
  await expect(row).toContainText('150 bpm')
})

test('six-week filter excludes older activity from the visible list and totals', async ({ page }) => {
  await openDashboard(page, [activity(13, 1, 8000, 2400), activity(14, 50, 12000, 3600)])

  await expect(page.getByTestId('activity-13')).toBeVisible()
  await expect(page.getByTestId('activity-14')).toHaveCount(0)
  await expect(page.getByTestId('run-count')).toHaveText('1')
  await expect(page.getByTestId('total-distance')).toHaveText('8.00 km')
  await expect(page.getByTestId('total-time')).toHaveText('40 min')
})

test('weekly buckets show correct run counts, distances and times', async ({ page }) => {
  await openDashboard(page, [activity(15, 1, 10000, 3600), activity(16, 10, 5000, 1800)])

  await expect(page.getByTestId('week-4')).toContainText('1 Lauf')
  await expect(page.getByTestId('week-4')).toContainText('5.00 km')
  await expect(page.getByTestId('week-4')).toContainText('30 min')
  await expect(page.getByTestId('week-5')).toContainText('1 Lauf')
  await expect(page.getByTestId('week-5')).toContainText('10.00 km')
  await expect(page.getByTestId('week-5')).toContainText('1 h 00 min')
})

test('clicking a run from the dashboard navigates to its activity route', async ({ page }) => {
  await openDashboard(page, [activity(17, 1, 10000, 3600)])
  await page.getByTestId('activity-17').click()

  await expect(page).toHaveURL(/\/activities\/17$/)
  await expect(page.getByText(/Lauf 17/)).toBeVisible()
})

test('empty data shows an empty state without invalid values', async ({ page }) => {
  await openDashboard(page, [])

  await expect(page.getByTestId('empty-state')).toContainText('Keine Läufe')
  await expect(page.locator('body')).not.toContainText(/NaN|undefined|null/)
})
