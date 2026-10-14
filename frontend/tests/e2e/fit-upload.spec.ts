import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

const fixture = (name: string) => path.resolve(import.meta.dirname, `../../../backend/src/test/resources/fit/${name}`)

test('imports FIT files from the dashboard and keeps persisted metrics correct for duplicates and mixed batches', async ({ page }) => {
  await page.clock.install({ time: new Date('2024-01-09T12:00:00Z') })
  await page.goto('/')
  await expect(page.getByTestId('empty-state')).toBeVisible()

  await page.getByRole('link', { name: 'FIT-Dateien importieren' }).click()
  await page.getByTestId('fit-picker').setInputFiles(fixture('synthetic-running.fit'))
  const firstUpload = page.waitForResponse((response) => response.url().endsWith('/api/imports') && response.request().method() === 'POST')
  await expect(page.getByTestId('import-row-synthetic-running.fit')).toContainText('PENDING — Select “Import selected files” to start.')
  await page.getByTestId('start-import').click()
  const firstResponse = await firstUpload
  expect(firstResponse.ok()).toBeTruthy()
  expect((await firstResponse.json()).outcomes[0].status).toBe('IMPORTED')
  await expect(page.getByTestId('import-row-synthetic-running.fit')).toContainText('Imported successfully.')
  await page.getByRole('link', { name: 'Back to dashboard' }).click()

  const firstRun = page.getByTestId('activity-1')
  await expect(firstRun).toBeVisible()
  await expect(firstRun.locator('time')).toHaveAttribute('datetime', '2024-01-01T12:00:00Z')
  await expect(firstRun).toContainText('5.00 km')
  await expect(firstRun).toContainText('30 min')
  await expect(firstRun).toContainText('6:00 min/km')
  await expect(firstRun).toContainText('145 bpm')
  await expect(page.getByTestId('run-count')).toHaveText('1')
  await expect(page.getByTestId('total-distance')).toHaveText('5.00 km')
  await expect(page.getByTestId('total-time')).toHaveText('30 min')
  await expect(page.getByTestId('average-distance')).toHaveText('5.00 km')
  await expect(page.getByTestId('average-pace')).toHaveText('6:00 min/km')
  await expect(page.getByTestId('week-4')).toContainText('1 Lauf')
  await expect(page.getByTestId('week-4')).toContainText('5.00 km')

  await firstRun.click()
  await expect(page).toHaveURL(/\/activities\/1$/)
  await expect(page.getByTestId('pace-chart')).toHaveAttribute('aria-label', /Pace-Diagramm \([1-9]\d* Werte\)/)
  await expect(page.getByTestId('elevation-chart')).toHaveAttribute('aria-label', /Höhenprofil-Diagramm \([1-9]\d* Werte\)/)
  await page.getByRole('link', { name: /Zurück zum Dashboard/ }).click()

  await page.getByRole('link', { name: 'FIT-Dateien importieren' }).click()
  await page.getByTestId('fit-picker').setInputFiles(fixture('synthetic-running.fit'))
  await page.getByTestId('start-import').click()
  await expect(page.getByTestId('import-row-synthetic-running.fit')).toContainText('Already imported.')
  await page.getByRole('link', { name: 'Back to dashboard' }).click()
  await expect(page.getByTestId('run-count')).toHaveText('1')
  await expect(page.getByTestId('total-distance')).toHaveText('5.00 km')
  await expect(page.getByTestId('total-time')).toHaveText('30 min')
  await expect(page.getByTestId('week-4')).toContainText('1 Lauf')

  await page.getByRole('link', { name: 'FIT-Dateien importieren' }).click()
  await page.getByTestId('fit-picker').setInputFiles([
    { name: 'synthetic-running.fit', mimeType: 'application/octet-stream', buffer: await readFile(fixture('synthetic-running.fit')) },
    { name: 'synthetic-running-2.fit', mimeType: 'application/octet-stream', buffer: await readFile(fixture('synthetic-running-2.fit')) },
    { name: 'broken.fit', mimeType: 'application/octet-stream', buffer: Buffer.from([1, 2, 3]) },
  ])
  await page.getByTestId('start-import').click()
  await expect(page.getByTestId('import-row-synthetic-running.fit').last()).toContainText('Already imported.')
  await expect(page.getByTestId('import-row-synthetic-running-2.fit')).toContainText('Imported successfully.')
  await expect(page.getByTestId('import-row-broken.fit')).toContainText(/invalid|failed/i)
  await page.getByRole('link', { name: 'Back to dashboard' }).click()

  const secondRun = page.getByTestId('activity-2')
  await expect(secondRun).toBeVisible()
  await expect(secondRun.locator('time')).toHaveAttribute('datetime', '2024-01-08T12:00:00Z')
  await expect(secondRun).toContainText('7.00 km')
  await expect(secondRun).toContainText('30 min')
  await expect(secondRun).toContainText('4:17 min/km')
  await expect(page.getByTestId('run-count')).toHaveText('2')
  await expect(page.getByTestId('total-distance')).toHaveText('12.00 km')
  await expect(page.getByTestId('total-time')).toHaveText('1 h 00 min')
  await expect(page.getByTestId('average-distance')).toHaveText('6.00 km')
  await expect(page.getByTestId('average-pace')).toHaveText('5:00 min/km')
  await expect(page.getByTestId('week-4')).toContainText('1 Lauf')
  await expect(page.getByTestId('week-4')).toContainText('5.00 km')
  await expect(page.getByTestId('week-5')).toContainText('1 Lauf')
  await expect(page.getByTestId('week-5')).toContainText('7.00 km')
})
