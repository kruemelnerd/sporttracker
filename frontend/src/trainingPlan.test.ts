import { describe, expect, it } from 'vitest'
import { initialPlanWeek, isoWeekLabel, mondayOfLocalDate, shiftWeek, summarizeWeek, phaseForWeek } from './trainingPlan'

describe('training plan calendar', () => {
  it('uses Monday ISO weeks across year boundaries without shifting calendar dates', () => {
    expect(mondayOfLocalDate('2027-01-01')).toBe('2026-12-28')
    expect(isoWeekLabel('2026-12-28')).toBe('KW 53 · 2026')
    expect(shiftWeek('2026-12-28', 1)).toBe('2027-01-04')
  })

  it('centres the current week when it is in the plan and clamps otherwise', () => {
    expect(initialPlanWeek('2026-09-21', '2027-03-14', '2026-10-21')).toBe('2026-10-19')
    expect(initialPlanWeek('2026-09-21', '2027-03-14', '2026-08-01')).toBe('2026-09-21')
    expect(initialPlanWeek('2026-09-21', '2027-03-14', '2027-04-01')).toBe('2027-03-08')
  })

  it('counts RUN distance separately and includes supplied durations only', () => {
    expect(summarizeWeek([
      { sport: 'RUN', targetDistanceKm: 8, targetDurationMinutes: null },
      { sport: 'BIKE', targetDistanceKm: 60, targetDurationMinutes: 150 },
      { sport: 'STRENGTH', targetDistanceKm: null, targetDurationMinutes: 30 },
    ])).toEqual({ runCount: 1, runDistanceKm: 8, durationMinutes: 180, otherSessionCount: 2 })
  })

  it('finds the phase overlapping the selected week and displays an empty phase otherwise', () => {
    expect(phaseForWeek([{ name: 'Grundlage', startDate: '2026-09-21', endDate: '2026-10-18' }], '2026-10-12')?.name).toBe('Grundlage')
    expect(phaseForWeek([], '2026-10-12')).toBeNull()
  })
})
