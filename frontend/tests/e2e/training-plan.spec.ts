import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

function localDateString(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

test('training plan CRUD, week navigation and JSON export/import persist in the isolated E2E database', async ({ page }) => {
  const name = `M6 e2e ${Date.now()}`
  const now = new Date()
  const startDate = `${now.getFullYear() - 1}-01-01`
  const endDate = `${now.getFullYear() + 1}-12-31`
  await page.goto('/training-plan')
  await expect(page.getByRole('heading', { name: 'Trainingsplan', exact: true })).toBeVisible()
  await expect(page.getByText('Noch kein Trainingsplan vorhanden.')).toBeVisible()

  await page.getByRole('button', { name: 'Trainingsplan erstellen' }).click()
  await page.getByLabel('Name', { exact: true }).fill(name)
  await page.getByLabel('Wettkampfziel').fill('Halbmarathon')
  await page.getByLabel('Planbeginn').fill(startDate)
  await page.getByLabel('Planende').fill(endDate)
  await page.getByLabel('Wettkampfdatum (optional)').fill(localDateString(new Date(now.getFullYear() + 1, 2, 14)))
  await page.getByRole('button', { name: 'Trainingsplan speichern' }).click()
  await expect(page.locator('.plan-intro h2')).toHaveText(name)

  await page.getByRole('button', { name: 'Plan bearbeiten' }).click()
  await page.getByLabel('Wettkampfziel').fill('Halbmarathon 21,1 km')
  await page.getByRole('button', { name: 'Änderungen speichern' }).click()
  await expect(page.locator('.plan-intro')).toContainText('Halbmarathon 21,1 km')

  await page.getByRole('button', { name: 'Phase hinzufügen' }).click()
  await page.getByLabel('Name', { exact: true }).last().fill('Grundlagen')
  await page.getByLabel('Beginn').fill(startDate)
  await page.getByLabel('Ende').fill(`${now.getFullYear()}-06-30`)
  await page.getByRole('button', { name: 'Phase speichern' }).click()
  await expect(page.getByText('Grundlagen', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Training hinzufügen' }).click()
  await page.getByLabel('Titel').fill('Locker am Wochenanfang')
  await page.getByLabel('Zieldistanz (km, optional)').fill('7.5')
  await page.getByLabel('Intensität RPE (1–10, optional)').fill('3')
  await page.getByRole('button', { name: 'Einheit speichern' }).click()
  const sessionEdit = page.getByRole('button', { name: 'Training bearbeiten: Locker am Wochenanfang' })
  await expect(sessionEdit).toBeVisible()
  await expect(page.getByTestId('run-distance')).toHaveText('7,5 km')

  await sessionEdit.click()
  const moved = new Date(`${await page.getByLabel('Datum').inputValue()}T12:00:00`)
  moved.setDate(moved.getDate() + 1)
  const movedDate = localDateString(moved)
  await page.getByLabel('Datum').fill(movedDate)
  await page.getByLabel('Titel').last().fill('Verschobener Lauf')
  await page.getByRole('button', { name: 'Einheit speichern' }).click()
  await expect(page.getByTestId(`week-day-${movedDate}`)).toContainText('Verschobener Lauf')

  const weekHeading = await page.locator('#week-heading').innerText()
  await page.getByRole('button', { name: 'Nächste Woche' }).click()
  await expect(page.locator('#week-heading')).not.toHaveText(weekHeading)
  await expect(page.getByText('Keine geplanten Einheiten in dieser Woche.')).toBeVisible()
  await page.getByRole('button', { name: 'Vorige Woche' }).click()

  await page.reload()
  await expect(page.locator('.plan-intro h2')).toHaveText(name)
  await expect(page.getByRole('button', { name: 'Training bearbeiten: Verschobener Lauf' })).toBeVisible()

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: 'JSON exportieren' }).click(),
  ])
  const exported = await readFile((await download.path())!)
  expect(download.suggestedFilename()).toMatch(/\.json$/)
  await page.locator('#training-plan-file').setInputFiles({ name: 'exported-plan.json', mimeType: 'application/json', buffer: exported })
  await expect(page.getByLabel('Importvorschau')).toContainText(name)
  await page.getByRole('button', { name: 'Import bestätigen' }).click()
  await expect(page.getByRole('alert')).toContainText('bereits vorhanden')
  await page.getByRole('button', { name: 'Bestehenden Plan bewusst ersetzen' }).click()
  await expect(page.locator('.plan-intro h2')).toHaveText(name)
  await expect(page.getByRole('button', { name: 'Training bearbeiten: Verschobener Lauf' })).toBeVisible()

  await page.locator('#training-plan-file').setInputFiles({
    name: 'invalid.json', mimeType: 'application/json', buffer: Buffer.from('{"schemaVersion":99,"plan":{}}'),
  })
  await expect(page.getByRole('alert')).toContainText('Schema-Version')
  await expect(page.getByRole('button', { name: 'Import bestätigen' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Training bearbeiten: Verschobener Lauf' })).toBeVisible()

  for (const width of [375, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
  }

  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Plan löschen' }).click()
  await expect(page.getByText('Noch kein Trainingsplan vorhanden.')).toBeVisible()
})
