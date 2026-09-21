import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

test('imports the committed running FIT fixture', async ({ page, request }) => {
  const fixture = path.resolve(import.meta.dirname, '../../../backend/src/test/resources/fit/20053968686_ACTIVITY.fit')
  await page.goto('/import')
  await page.getByTestId('fit-picker').setInputFiles(fixture)
  await expect(page.getByTestId('import-outcome')).toContainText('IMPORTED')
  const response = await request.post('/api/imports', {
    multipart: { file: { name: 'activity.fit', mimeType: 'application/octet-stream', buffer: await readFile(fixture) } },
  })
  expect(response.status()).toBe(200)
  expect((await response.json()).outcomes[0].status).toBe('DUPLICATE')
})
