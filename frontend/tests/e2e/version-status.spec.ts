import { expect, test } from '@playwright/test'

const installedDigest = `sha256:${'a'.repeat(64)}`
const latestDigest = `sha256:${'b'.repeat(64)}`
const installedCommit = 'c'.repeat(40)
const latestCommit = 'd'.repeat(40)

test('version footer supports keyboard details, full digest copying, refresh and mobile layouts', async ({ page, context }, testInfo) => {
  const requests: string[] = []
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.route('**/api/activities?*', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/version*', async (route) => {
    const url = new URL(route.request().url())
    requests.push(url.pathname + url.search)
    expect(route.request().method()).toBe('GET')
    await route.fulfill({ json: {
      applicationVersion: '1.2.3', installedCommit, installedDigest, latestCommit: url.search ? installedCommit : latestCommit, latestDigest,
      updateStatus: url.search ? 'REBUILD_AVAILABLE' : 'DIFFERENT_RELEASE',
      lastCheckedAt: url.search ? '2026-11-07T10:02:00Z' : '2026-11-07T10:00:00Z', errorStatus: null,
    } })
  })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/')
  const summary = page.locator('footer summary')
  const details = page.locator('footer details')
  await expect(summary).toContainText('Anderes Release veröffentlicht')
  await expect(summary).not.toContainText('Update verfügbar')
  await summary.focus()
  await summary.press('Enter')
  await expect(details).toHaveAttribute('open', '')
  await expect(summary).toHaveCSS('outline-style', 'solid')
  await expect(page.getByRole('heading', { name: 'Versionsinformationen' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Installierte Version: Commit/ })).toHaveAttribute('href',
    `https://github.com/kruemelnerd/sporttracker/commit/${installedCommit}`)
  for (const link of await page.locator('footer a').all()) {
    expect(await link.evaluate((element) => element.getBoundingClientRect().height)).toBeGreaterThanOrEqual(44)
  }

  for (const [label, digest] of [['Installierten Digest kopieren', installedDigest], ['Registry-Digest kopieren', latestDigest]]) {
    await expect(page.locator('footer code').filter({ hasText: digest })).toBeVisible()
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(digest)
  }

  for (const width of [375, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width)
    await expect(page.locator('footer code').first()).toHaveText(installedDigest)
    await expect(page.locator('footer code').last()).toHaveText(latestDigest)
    if (width === 375 || width === 1440) {
      await page.screenshot({ path: testInfo.outputPath(`version-${width}.png`), fullPage: true })
    }
  }
  expect(requests).toEqual(['/api/version'])
  const refresh = page.getByRole('button', { name: 'Auf Updates prüfen', exact: true })
  await refresh.focus()
  await refresh.press('Enter')
  await expect(summary).toContainText('Neuer Build verfügbar')
  await expect(page.locator('footer time')).toHaveAttribute('datetime', '2026-11-07T10:02:00Z')
  expect(requests).toEqual(['/api/version', '/api/version?refresh=true'])
  await refresh.press('Escape')
  await expect(details).not.toHaveAttribute('open')
  await expect(summary).toBeFocused()
  await summary.press('Space')
  await expect(page.getByRole('heading', { name: 'Versionsinformationen' })).toBeVisible()
})
