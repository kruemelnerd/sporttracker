import { afterEach, describe, expect, it, vi } from 'vitest'
import { exportTrainingPlan, getPlannedSessions, importTrainingPlan, listTrainingPlans } from './trainingPlansApi'
import type { TrainingPlanDocument } from './trainingPlanJson'

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

  it('uses the same versioned document for export and explicit replacement import', async () => {
    const document: TrainingPlanDocument = { schemaVersion: 1, plan: {
      name: 'Plan', goal: '10 km', startDate: '2026-01-01', endDate: '2026-02-01', raceDate: null, targetTime: null, description: null, phases: [], sessions: [],
    } }
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => document })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ plan: {}, replacedExisting: true }) })
    vi.stubGlobal('fetch', fetchMock)

    expect(await exportTrainingPlan('plan-1')).toEqual(document)
    await importTrainingPlan(document, true)

    expect(fetchMock.mock.calls[0][0]).toBe('/api/training-plans/plan-1/export')
    expect(JSON.parse(fetchMock.mock.calls[1][1].body as string)).toEqual({ ...document, replaceExisting: true })
  })
})
