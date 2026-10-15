import { describe, expect, it } from 'vitest'
import { parseTrainingPlanDocument } from './trainingPlanJson'

const valid = JSON.stringify({
  schemaVersion: 1,
  plan: {
    name: 'Plan', goal: 'Halbmarathon', startDate: '2026-09-21', endDate: '2027-03-14', raceDate: '2027-03-14',
    description: 'Vorbereitung', phases: [{ name: 'Grundlage', startDate: '2026-09-21', endDate: '2026-10-18' }],
    sessions: [{ date: '2026-10-20', sport: 'RUN', sessionType: 'EASY', title: 'Locker', targetDistanceKm: 8, targetRpe: 3 }],
  },
})

describe('training plan JSON validation', () => {
  it('parses a versioned plan with phases and sessions', () => {
    expect(parseTrainingPlanDocument(valid).plan.sessions[0].targetDistanceKm).toBe(8)
  })

  it('rejects unsupported versions, invalid calendar dates and out-of-range effort', () => {
    expect(() => parseTrainingPlanDocument(valid.replace('"schemaVersion":1', '"schemaVersion":2'))).toThrow('Schema-Version')
    expect(() => parseTrainingPlanDocument(valid.replace('"targetRpe":3', '"targetRpe":11'))).toThrow('RPE')
    expect(() => parseTrainingPlanDocument(valid.replace('2026-10-20', '2026-02-30'))).toThrow('Datum')
  })

  it('rejects unknown fields rather than silently discarding them', () => {
    expect(() => parseTrainingPlanDocument(valid.replace('"goal":"Halbmarathon"', '"goal":"Halbmarathon","unknown":true'))).toThrow('Unbekanntes Feld')
  })
})
