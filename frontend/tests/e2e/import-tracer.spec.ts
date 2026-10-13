import { expect, test } from '@playwright/test'
import path from 'node:path'

test('opens the existing import view from the dashboard and accepts a FIT file', async ({ page }) => {
  const fixture = path.resolve(import.meta.dirname, '../../../backend/src/test/resources/fit/synthetic-running.fit')
  await page.goto('/')
  await expect(page.getByRole('link', { name: 'FIT-Dateien importieren' })).toBeVisible()
  await page.getByRole('link', { name: 'FIT-Dateien importieren' }).click()
  await expect(page).toHaveURL(/\/import$/)
  await page.getByTestId('fit-picker').setInputFiles(fixture)
  await expect(page.getByTestId('import-row-synthetic-running.fit')).toContainText('PENDING')
  await expect(page.getByTestId('start-import')).toBeEnabled()
})
