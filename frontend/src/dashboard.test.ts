import { describe, expect, it } from 'vitest'
import { summarizeActivities, type Activity } from './dashboard'

const now = new Date('2026-10-12T12:00:00Z')
const activities: Activity[] = [
  { id: 1, sport: 'running', subSport: 'trail', startTime: '2026-10-10T08:00:00Z', elapsedSeconds: 3600, distanceMeters: 10000, averageHeartRate: 150, ascentMeters: 200 },
  { id: 2, sport: 'running', subSport: null, startTime: '2026-10-05T08:00:00Z', elapsedSeconds: 1800, distanceMeters: 5000, averageHeartRate: null, ascentMeters: null },
  { id: 3, sport: 'running', subSport: null, startTime: '2026-08-31T12:00:00Z', elapsedSeconds: 1000, distanceMeters: 3000, averageHeartRate: 140, ascentMeters: 30 },
]

describe('six-week dashboard aggregation', () => {
  it('includes the exact six-week boundary and excludes older or future activities', () => {
    const result = summarizeActivities([...activities, { ...activities[0], id: 4, startTime: '2026-10-12T12:00:01Z' }], now)
    expect(result.activities.map(({ id }) => id)).toEqual([1, 2, 3])
  })

  it('aggregates totals and computes pace from total time over total distance', () => {
    const result = summarizeActivities(activities, now)
    expect(result.metrics).toEqual({ runCount: 3, distanceMeters: 18000, durationSeconds: 6400, averageDistanceMeters: 6000, paceSecondsPerKm: 6400 / 18, ascentMeters: 230 })
  })

  it('returns six chronological weekly buckets and newest-first activities', () => {
    const result = summarizeActivities(activities, now)
    expect(result.weeks).toHaveLength(6)
    expect(result.weeks.map(({ start }) => start)).toEqual([
      '2026-08-31T12:00:00.000Z', '2026-09-07T12:00:00.000Z', '2026-09-14T12:00:00.000Z',
      '2026-09-21T12:00:00.000Z', '2026-09-28T12:00:00.000Z', '2026-10-05T12:00:00.000Z',
    ])
    expect(result.weeks.map(({ runCount }) => runCount)).toEqual([1, 0, 0, 0, 1, 1])
    expect(result.weeks.map(({ distanceMeters }) => distanceMeters)).toEqual([3000, 0, 0, 0, 5000, 10000])
    expect(result.weeks.map(({ durationSeconds }) => durationSeconds)).toEqual([1000, 0, 0, 0, 1800, 3600])
    expect(result.activities.map(({ id }) => id)).toEqual([1, 2, 3])
  })

  it('keeps missing measurements unavailable and produces a safe empty result', () => {
    const sparse = [{ ...activities[0], elapsedSeconds: null, distanceMeters: null, ascentMeters: null }]
    const result = summarizeActivities(sparse, now)
    expect(result.metrics.distanceMeters).toBeNull()
    expect(result.metrics.durationSeconds).toBeNull()
    expect(result.weeks[5].distanceMeters).toBeNull()
    expect(result.metrics.paceSecondsPerKm).toBeNull()
    expect(result.metrics.ascentMeters).toBeNull()
    expect(summarizeActivities([], now).metrics.runCount).toBe(0)
  })
})
