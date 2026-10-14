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

test('dashboard panels scan clearly on desktop and retain activity data on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await openDashboard(page, [activity(21, 1, 9400, 3300, { averageHeartRate: 148, subSport: 'trail_run' })])
  await expect(page.getByRole('heading', { name: 'Wochenvolumen' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Läufe pro Woche' })).toBeVisible()
  await expect(page.getByTestId('week-bar-5')).toContainText('9.40 km')
  await expect(page.getByTestId('activity-21')).toContainText('trail run')

  await page.setViewportSize({ width: 375, height: 812 })
  await expect(page.getByTestId('activity-21')).toContainText('9.40 km')
  await expect(page.getByTestId('activity-21')).toContainText('5:51 min/km')
  await expect(page.getByTestId('activity-21')).toContainText('148 bpm')
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
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
  await page.route('**/api/activities?*', (route) => route.fulfill({ json: [activity(17, 1, 10000, 3600)] }))
  await page.route('**/api/activities/17', (route) => route.fulfill({ json: detail(17) }))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Letzte sechs Wochen' })).toBeVisible()
  await page.getByTestId('activity-17').click()

  await expect(page).toHaveURL(/\/activities\/17$/)
  await expect(page.locator('.detail > header')).toContainText('6:00 min/km')
  await expect(page.getByText('Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.')).toBeVisible()
  await expect(page.getByTestId('pace-chart')).toBeVisible()
  await expect(page.getByTestId('heart-rate-chart')).toBeVisible()
  await expect(page.getByRole('link', { name: /Zurück zum Dashboard/ })).toBeVisible()
})

const detail = (id: number, withGps = false) => ({
  activity: { ...activity(id, 1, 5000, 1800), averageHeartRate: 145, ascentMeters: 20 },
  movingSeconds: 1700,
  averageSpeedMetersPerSecond: 2.78,
  maximumHeartRate: 160,
  averageCadence: 84,
  calories: 300,
  records: [
    { elapsedSeconds: 0, distanceMeters: 0, latitude: withGps ? 50 : null, longitude: withGps ? 8 : null, altitudeMeters: 100, speedMetersPerSecond: null, heartRate: 130, cadence: 70 },
    { elapsedSeconds: 253.02, distanceMeters: 100, latitude: withGps ? 50.001 : null, longitude: withGps ? 8.001 : null, altitudeMeters: 101, speedMetersPerSecond: null, heartRate: 132, cadence: 72 },
    { elapsedSeconds: 853.02, distanceMeters: 1600, latitude: withGps ? 50.005 : null, longitude: withGps ? 8.005 : null, altitudeMeters: 105, speedMetersPerSecond: null, heartRate: 145, cadence: 82 },
    { elapsedSeconds: 1453.02, distanceMeters: 3100, latitude: withGps ? 50.008 : null, longitude: withGps ? 8.008 : null, altitudeMeters: 110, speedMetersPerSecond: null, heartRate: 150, cadence: 84 },
  ],
  laps: [{ lapNumber: 1, durationSeconds: 1800, distanceMeters: 5000, averageSpeedMetersPerSecond: null, averageHeartRate: 145, averageCadence: 84, ascentMeters: 20, descentMeters: 5 }],
})

test('direct activity URL loads detail with GPS map and recorded laps', async ({ page }) => {
  await page.route('https://tile.openstreetmap.org/**', (route) => route.abort())
  await page.route('**/api/activities/18', (route) => route.fulfill({ json: detail(18, true) }))
  await page.goto('/activities/18')
  await expect(page.locator('.detail > header')).toContainText('5.00 km')
  await expect(page.getByTestId('heart-rate-chart')).toBeVisible()
  await expect(page.getByRole('table')).toContainText('5.00 km')
  await expect(page.getByRole('cell', { name: '6:00 min/km' })).toBeVisible()
  await expect(page.getByText('Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.')).toHaveCount(0)
  await expect(page.locator('.leaflet-container')).toBeVisible()
  await expect(page.locator('.leaflet-overlay-pane path')).toHaveCount(1)
  await expect(page.locator('[data-testid="pace-chart"] canvas')).toHaveCount(1)
  await expect(page.getByTestId('pace-chart')).toHaveAttribute('aria-label', 'Pace-Diagramm (2 Werte)')
  await expect(page.locator('[data-testid="heart-rate-chart"] canvas')).toHaveCount(1)
  await expect(page.locator('[data-testid="elevation-chart"] canvas')).toHaveCount(1)
  await expect(page.getByTestId('elevation-chart')).toHaveAttribute('aria-label', 'Höhenprofil-Diagramm (4 Werte)')
})

test('unknown activity URL shows a clear not-found message', async ({ page }) => {
  await page.route('**/api/activities/999', (route) => route.fulfill({ status: 404, json: { status: 404 } }))
  await page.goto('/activities/999')
  await expect(page.getByRole('alert')).toHaveText('Lauf nicht gefunden.')
})

test('empty data shows an empty state without invalid values', async ({ page }) => {
  await openDashboard(page, [])

  await expect(page.getByTestId('empty-state')).toContainText('Keine Läufe')
  await expect(page.locator('body')).not.toContainText(/NaN|undefined|null/)
})
