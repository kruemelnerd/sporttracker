import { afterEach, describe, expect, it, vi } from 'vitest'
import { getPlannedSessions, listTrainingPlans } from './trainingPlansApi'

afterEach(() => vi.unstubAllGlobals())

describe('training plan API', () => {
  it('loads plan summaries and date-bounded planned sessions from the local API', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
    vi.stubGlobal('fetch', fetchMock)

    await listTrainingPlans()
    await getPlannedSessions('plan-id', '2026-12-28', '2027-01-03')

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      '/api/training-plans',
      '/api/training-plans/plan-id/sessions?from=2026-12-28&to=2027-01-03',
    ])
  })

  it('surfaces the backend validation message for rejected operations', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({ message: 'RPE muss zwischen 1 und 10 liegen.' }) }))

    await expect(listTrainingPlans()).rejects.toThrow('RPE muss zwischen 1 und 10 liegen.')
  })
})
