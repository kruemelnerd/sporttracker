import { afterEach, describe, expect, it, vi } from 'vitest'
import { getVersion } from './api'

afterEach(() => vi.unstubAllGlobals())

describe('version API', () => {
  it('loads the backend DTO and uses only the backend refresh query for manual checks', async () => {
    const data = { applicationVersion: '1.2.3', installedCommit: null, installedDigest: null,
      latestCommit: null, latestDigest: null, updateStatus: 'UNKNOWN', lastCheckedAt: null, errorStatus: null }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => data })
    vi.stubGlobal('fetch', fetchMock)

    expect(await getVersion()).toEqual(data)
    expect(await getVersion(true)).toEqual(data)
    expect(fetchMock.mock.calls).toEqual([['/api/version'], ['/api/version?refresh=true']])
  })

  it('does not expose an unsuccessful response body or credentials as an error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500,
      text: async () => 'private-token stacktrace', json: async () => ({ message: 'private-token' }) }))
    await expect(getVersion()).rejects.toThrow('Versionsinformationen konnten nicht geladen werden.')
  })
})
