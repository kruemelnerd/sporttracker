import { expect, test, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'

const activity = {
  id: 31,
  sport: 'running',
  subSport: 'trail_run',
  startTime: '2026-10-12T10:00:00Z',
  elapsedSeconds: 1800,
  distanceMeters: 5000,
  averageHeartRate: 145,
  ascentMeters: 20,
}

const detail = {
  activity,
  movingSeconds: 1750,
  averageSpeedMetersPerSecond: 2.78,
  maximumHeartRate: 160,
  averageCadence: 84,
  calories: 300,
  records: [
    { elapsedSeconds: 0, distanceMeters: 0, latitude: 50, longitude: 8, altitudeMeters: 100, speedMetersPerSecond: 2.5, heartRate: 130, cadence: 70 },
    { elapsedSeconds: 300, distanceMeters: 1000, latitude: 50.001, longitude: 8.001, altitudeMeters: 105, speedMetersPerSecond: 3.3, heartRate: 140, cadence: 82 },
    { elapsedSeconds: 600, distanceMeters: 2000, latitude: 50.002, longitude: 8.002, altitudeMeters: 110, speedMetersPerSecond: 3.3, heartRate: 145, cadence: 84 },
  ],
  laps: [{ lapNumber: 1, durationSeconds: 1800, distanceMeters: 5000, averageSpeedMetersPerSecond: 2.78, averageHeartRate: 145, averageCadence: 84, ascentMeters: 20, descentMeters: 5 }],
}

async function routeData(page: Page) {
  await page.route('**/api/activities?*', (route) => route.fulfill({ json: [activity] }))
  await page.route('**/api/activities/31', (route) => route.fulfill({ json: detail }))
  await page.route('https://tile.openstreetmap.org/**', (route) => route.abort())
}

async function noPageOverflow(page: Page, width: number) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
}

test('all M5 views fit target widths and capture visual checkpoints', async ({ page }) => {
  await routeData(page)
  await mkdir('/tmp/opencode/sporttracker-m5', { recursive: true })

  for (const width of [375, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })

    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'Letzte sechs Wochen' })).toBeVisible()
    await expect(page.getByTestId('total-distance')).toBeVisible()
    await noPageOverflow(page, width)
    if (width === 375 || width === 1440) {
      await page.screenshot({ path: `/tmp/opencode/sporttracker-m5/dashboard-${width}.png`, fullPage: true })
    }

    await page.goto('/import')
    await expect(page.getByRole('heading', { name: 'FIT-Dateien importieren' })).toBeVisible()
    await expect(page.getByLabel('FIT-Dateien auswählen')).toBeVisible()
    await noPageOverflow(page, width)
    if (width === 375 || width === 1440) {
      await page.screenshot({ path: `/tmp/opencode/sporttracker-m5/import-${width}.png`, fullPage: true })
    }

    await page.goto('/activities/31')
    await expect(page.getByTestId('pace-chart')).toBeVisible()
    await expect(page.locator('.leaflet-container')).toBeVisible()
    await expect(page.getByRole('table')).toBeVisible()
    await noPageOverflow(page, width)
    if (width === 375 || width === 1440) {
      await page.screenshot({ path: `/tmp/opencode/sporttracker-m5/detail-${width}.png`, fullPage: true })
    }
  }
})

test('keyboard navigation, import activation, error announcements and reduced motion work', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await routeData(page)
  await page.goto('/')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: 'Zum Inhalt springen' })).toBeFocused()
  await expect(page.getByRole('link', { name: 'Zum Inhalt springen' })).toHaveCSS('outline-style', 'solid')
  await expect(page.getByRole('link', { name: 'Übersicht' })).toHaveAttribute('aria-current', 'page')
  await page.getByRole('button', { name: 'Menü öffnen' }).click()
  await expect(page.getByRole('button', { name: 'Menü schließen' })).toHaveAttribute('aria-expanded', 'true')

  await page.goto('/import')
  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByTestId('fit-drop-zone').focus()
  await page.getByTestId('fit-drop-zone').press('Enter')
  await (await chooserPromise).setFiles({ name: 'keyboard.fit', mimeType: 'application/octet-stream', buffer: Buffer.from([1]) })
  await expect(page.getByTestId('selection-count')).toContainText('1 Datei ausgewählt')

  await page.route('**/api/activities?*', (route) => route.fulfill({ status: 500, body: 'error' }))
  await page.goto('/')
  await expect(page.getByRole('alert')).toBeVisible()

  await page.emulateMedia({ reducedMotion: 'reduce' })
  const duration = await page.getByRole('link', { name: 'FIT-Dateien importieren' }).evaluate((element) => getComputedStyle(element).transitionDuration)
  expect(Number.parseFloat(duration)).toBeLessThanOrEqual(0.001)
})
