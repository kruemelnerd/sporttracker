import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VersionResponse } from '../api'
import VersionIndicator from './VersionIndicator.vue'

const { getVersion } = vi.hoisted(() => ({ getVersion: vi.fn() }))
vi.mock('../api', () => ({ getVersion }))

const installedDigest = `sha256:${'a'.repeat(64)}`
const latestDigest = `sha256:${'b'.repeat(64)}`
const installedCommit = 'c'.repeat(40)
const latestCommit = 'd'.repeat(40)
const version = (overrides: Partial<VersionResponse> = {}): VersionResponse => ({
  applicationVersion: '1.2.3', installedCommit, installedDigest, latestCommit, latestDigest,
  updateStatus: 'CURRENT', lastCheckedAt: '2026-11-07T10:00:00Z', errorStatus: null, ...overrides,
})

async function loaded() {
  const wrapper = mount(VersionIndicator)
  await flushPromises()
  return wrapper
}

beforeEach(() => getVersion.mockReset().mockResolvedValue(version()))
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('VersionIndicator', () => {
  it.each([
    ['CURRENT', 'Version aktuell'],
    ['UPDATE_AVAILABLE', 'Update verfügbar'],
    ['REBUILD_AVAILABLE', 'Neuer Build verfügbar'],
    ['DIFFERENT_RELEASE', 'Anderes Release veröffentlicht'],
    ['UNKNOWN', 'Update-Status unbekannt'],
  ] as const)('renders %s without reinterpreting release order', async (updateStatus, text) => {
    getVersion.mockResolvedValue(version({ updateStatus }))
    const wrapper = await loaded()
    expect(wrapper.get('summary').text()).toContain(text)
    expect(wrapper.get('summary').text()).toContain('1.2.3')
    if (updateStatus === 'DIFFERENT_RELEASE') expect(wrapper.text()).not.toContain('Update verfügbar')
    expect(getVersion).toHaveBeenCalledOnce()
  })

  it('shows both complete digests, shortened GitHub links, and the last check time', async () => {
    const wrapper = await loaded()
    expect(wrapper.findAll('code').map((node) => node.text())).toEqual([installedDigest, latestDigest])
    const links = wrapper.findAll('a')
    expect(links.map((link) => link.attributes('href'))).toEqual([
      `https://github.com/kruemelnerd/sporttracker/commit/${installedCommit}`,
      `https://github.com/kruemelnerd/sporttracker/commit/${latestCommit}`,
    ])
    expect(links.map((link) => link.text())).toEqual([installedCommit.slice(0, 7), latestCommit.slice(0, 7)])
    for (const link of links) {
      expect(link.attributes('target')).toBe('_blank')
      expect(link.attributes('rel')).toContain('noopener')
      expect(link.attributes('aria-label')).toContain('GitHub')
    }
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-11-07T10:00:00Z')
    expect(wrapper.get('time').text()).toContain('07.11.2026')
  })

  it('explains missing build metadata and omits unavailable links and copy controls', async () => {
    getVersion.mockResolvedValue(version({ applicationVersion: '', installedCommit: null, installedDigest: null,
      latestCommit: null, latestDigest: null, lastCheckedAt: null, updateStatus: 'UNKNOWN' }))
    const wrapper = await loaded()
    expect(wrapper.text()).toContain('Version nicht verfügbar')
    expect(wrapper.text()).toContain('Für diese Installation ist kein Image-Digest verfügbar.')
    expect(wrapper.text()).toContain('Commit nicht verfügbar')
    expect(wrapper.text()).toContain('Registry-Digest nicht verfügbar')
    expect(wrapper.text()).toContain('Noch nicht geprüft')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('button[aria-label$="Digest kopieren"]').exists()).toBe(false)
  })

  it.each([
    ['AUTHENTICATION_REQUIRED', 'Anmeldung'], ['ACCESS_DENIED', 'Zugriff'],
    ['NOT_FOUND', 'nicht gefunden'], ['RATE_LIMITED', 'Zu viele'],
    ['REGISTRY_UNAVAILABLE', 'nicht erreichbar'], ['TIMEOUT', 'Zeitüberschreitung'],
    ['NETWORK_ERROR', 'Netzwerk'], ['INVALID_RESPONSE', 'ungültige Antwort'],
  ] as const)('explains %s safely and preserves installed metadata', async (errorStatus, message) => {
    getVersion.mockResolvedValue(version({ errorStatus, updateStatus: 'UNKNOWN', latestDigest: null, latestCommit: null }))
    const wrapper = await loaded()
    expect(wrapper.get('summary').text()).toContain('Update-Status unbekannt')
    expect(wrapper.get('[data-testid="version-error"]').text()).toContain(message)
    expect(wrapper.text()).toContain(installedDigest)
    expect(wrapper.text()).toContain('1.2.3')
    expect(wrapper.text()).not.toContain(errorStatus)
  })

  it('handles an unreachable API without exposing exceptions and allows a user retry', async () => {
    getVersion.mockRejectedValueOnce(new Error('private-token stacktrace'))
    const wrapper = await loaded()
    expect(wrapper.get('summary').text()).toContain('Update-Status unbekannt')
    expect(wrapper.get('[data-testid="version-error"]').text()).toContain('Versionsinformationen konnten nicht geladen werden')
    expect(wrapper.text()).not.toContain('private-token')
    expect(wrapper.text()).not.toContain('stacktrace')
    await wrapper.get('[data-testid="version-refresh"]').trigger('click')
    await flushPromises()
    expect(getVersion).toHaveBeenLastCalledWith(true)
    expect(wrapper.get('summary').text()).toContain('Version aktuell')
  })

  it('copies each full digest and announces success', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = await loaded()
    await wrapper.get('button[aria-label="Installierten Digest kopieren"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(installedDigest)
    expect(wrapper.get('[data-testid="version-feedback"]').text()).toContain('Installierter Digest kopiert')
    await wrapper.get('button[aria-label="Registry-Digest kopieren"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenLastCalledWith(latestDigest)
    expect(wrapper.get('[data-testid="version-feedback"]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-testid="version-feedback"]').text()).toContain('Registry-Digest kopiert')
  })

  it.each([true, false])('offers manual copying when clipboard access is denied or absent (%s)', async (available) => {
    vi.stubGlobal('navigator', available
      ? { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('private clipboard error')) } } : {})
    const wrapper = await loaded()
    await wrapper.get('button[aria-label="Installierten Digest kopieren"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="version-feedback"]').text()).toContain('manuell kopieren')
    expect(wrapper.text()).not.toContain('private clipboard error')
    expect(wrapper.get('code').text()).toBe(installedDigest)
  })

  it('shows initial loading and prevents concurrent checks until the response is available', async () => {
    let resolve!: (value: VersionResponse) => void
    getVersion.mockReturnValueOnce(new Promise<VersionResponse>((done) => { resolve = done }))
    const wrapper = mount(VersionIndicator)
    await nextTick()
    expect(wrapper.get('summary').text()).toContain('Versionsinformationen werden geladen')
    expect(wrapper.get('[data-testid="version-refresh"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="version-refresh"]').trigger('click')
    expect(getVersion).toHaveBeenCalledOnce()
    resolve(version())
    await flushPromises()
    expect(wrapper.get('[data-testid="version-refresh"]').attributes('disabled')).toBeUndefined()
  })

  it('refreshes only on interaction, prevents duplicate clicks, and renders the returned check time', async () => {
    const wrapper = await loaded()
    let resolve!: (value: VersionResponse) => void
    getVersion.mockReturnValueOnce(new Promise<VersionResponse>((done) => { resolve = done }))
    const refresh = wrapper.get('[data-testid="version-refresh"]')
    await refresh.trigger('click')
    expect(refresh.attributes('disabled')).toBeDefined()
    expect(refresh.attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toContain('Prüfung läuft')
    await refresh.trigger('click')
    expect(getVersion).toHaveBeenCalledTimes(2)
    expect(getVersion).toHaveBeenLastCalledWith(true)
    resolve(version({ updateStatus: 'REBUILD_AVAILABLE', lastCheckedAt: '2026-11-07T10:02:00Z' }))
    await flushPromises()
    expect(wrapper.get('summary').text()).toContain('Neuer Build verfügbar')
    expect(wrapper.get('time').attributes('datetime')).toBe('2026-11-07T10:02:00Z')
    expect(refresh.attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('eine Prüfung pro Minute')
  })

  it('retains installed identity but does not present stale remote metadata after a failed refresh', async () => {
    const wrapper = await loaded()
    getVersion.mockRejectedValueOnce(new Error('private connection detail'))
    await wrapper.get('[data-testid="version-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(installedDigest)
    expect(wrapper.text()).toContain('1.2.3')
    expect(wrapper.text()).not.toContain(latestDigest)
    expect(wrapper.get('summary').text()).toContain('Update-Status unbekannt')
  })

  it('provides a native keyboard disclosure, labelled details, and Escape returns focus to the summary', async () => {
    const wrapper = mount(VersionIndicator, { attachTo: document.body })
    await flushPromises()
    try {
      const summary = wrapper.get('summary')
      const details = wrapper.get('details').element as HTMLDetailsElement
      expect(details.open).toBe(false)
      const panel = wrapper.get(`#${summary.attributes('aria-controls')}`)
      expect(panel.attributes('aria-labelledby')).toBe(summary.attributes('id'))
      expect(panel.attributes('role')).toBe('region')
      expect(wrapper.get('[data-testid="version-refresh"]').attributes('type')).toBe('button')
      details.open = true
      await wrapper.get('[data-testid="version-refresh"]').trigger('keydown', { key: 'Escape' })
      expect(details.open).toBe(false)
      expect(document.activeElement).toBe(summary.element)
    } finally {
      wrapper.unmount()
    }
  })
})
