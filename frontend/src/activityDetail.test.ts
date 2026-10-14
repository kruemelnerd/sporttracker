import { describe, expect, it } from 'vitest'
import { chartSeries, descendedMeters, displayCadence, formatPace, lapPaceSecondsPerKm, lapRows, paceAxis } from './activityDetail'

describe('activity detail formatting and chart preparation', () => {
  it('formats pace and leaves unavailable or invalid values blank', () => {
    expect(formatPace(360)).toBe('6:00 min/km')
    expect(formatPace(null)).toBe('–')
    expect(formatPace(0)).toBe('–')
    expect(paceAxis(360)).toBe('6:00 min/km')
    expect(displayCadence(84)).toBe('168 steps/min')
  })

  it('uses distance for aligned series without filling missing measurements', () => {
    const rows = chartSeries([
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: 10, speedMetersPerSecond: 2, heartRate: 140, cadence: null },
      { elapsedSeconds: null, distanceMeters: 100, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: null, cadence: 80 },
      { elapsedSeconds: 40, distanceMeters: 200, latitude: null, longitude: null, altitudeMeters: 12, speedMetersPerSecond: 2, heartRate: 150, cadence: 82 },
    ])
    expect(rows.pace).toEqual([[0, 500], [100, null], [200, 500]])
    expect(rows.heartRate).toEqual([[0, 140], [100, null], [200, 150]])
    expect(rows.elevation).toEqual([[0, 10], [100, null], [200, 12]])
  })

  it('derives pace from cumulative FIT distance and elapsed time when speed is unavailable', () => {
    const series = chartSeries([
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: 100, speedMetersPerSecond: null, heartRate: 140, cadence: 80 },
      { elapsedSeconds: 300, distanceMeters: 1000, latitude: null, longitude: null, altitudeMeters: 102, speedMetersPerSecond: null, heartRate: 145, cadence: 82 },
      { elapsedSeconds: 600, distanceMeters: 2000, latitude: null, longitude: null, altitudeMeters: 101, speedMetersPerSecond: null, heartRate: 150, cadence: 84 },
    ])
    expect(series.pace).toEqual([[0, null], [1000, 300], [2000, 300]])
  })

  it('keeps 17-minute pace but drops slower or stopped points without affecting other series', () => {
    const series = chartSeries([
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: 100, speedMetersPerSecond: null, heartRate: 140, cadence: 80 },
      { elapsedSeconds: 253.02, distanceMeters: 100, latitude: null, longitude: null, altitudeMeters: 110, speedMetersPerSecond: null, heartRate: 145, cadence: 82 },
      { elapsedSeconds: 361.02, distanceMeters: 200, latitude: null, longitude: null, altitudeMeters: 120, speedMetersPerSecond: null, heartRate: 150, cadence: 84 },
      { elapsedSeconds: 463.02, distanceMeters: 300, latitude: null, longitude: null, altitudeMeters: 130, speedMetersPerSecond: null, heartRate: 155, cadence: 86 },
      { elapsedSeconds: 583.02, distanceMeters: 400, latitude: null, longitude: null, altitudeMeters: 140, speedMetersPerSecond: 1000 / (42.17 * 60), heartRate: 160, cadence: 88 },
    ])
    expect(series.pace.map(([, pace]) => pace === null)).toEqual([true, true, true, false, true])
    expect(series.pace[3][1]).toBeCloseTo(1020)
    expect(series.heartRate).toEqual([[0, 140], [100, 145], [200, 150], [300, 155], [400, 160]])
    expect(series.elevation).toEqual([[0, 100], [100, 110], [200, 120], [300, 130], [400, 140]])
  })

  it('reduces large series deterministically and retains endpoints and alignment', () => {
    const records = Array.from({ length: 4000 }, (_, index) => ({ elapsedSeconds: index, distanceMeters: index, latitude: null, longitude: null, altitudeMeters: index, speedMetersPerSecond: 1, heartRate: index, cadence: index }))
    const series = chartSeries(records)
    expect(series.pace.length).toBeLessThanOrEqual(1500)
    expect(series.pace[0][0]).toBe(0)
    expect(series.pace.at(-1)?.[0]).toBe(3999)
    expect(series.pace.map(([x]) => x)).toEqual(series.heartRate.map(([x]) => x))
  })

  it('prefers recorded laps and only derives kilometer splits when cumulative distance and time are complete', () => {
    const records = [
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: 10, speedMetersPerSecond: 2, heartRate: 140, cadence: 80 },
      { elapsedSeconds: 300, distanceMeters: 1000, latitude: null, longitude: null, altitudeMeters: 12, speedMetersPerSecond: 2, heartRate: 150, cadence: 82 },
      { elapsedSeconds: 600, distanceMeters: 2000, latitude: null, longitude: null, altitudeMeters: 11, speedMetersPerSecond: 2, heartRate: 152, cadence: 84 },
    ]
    expect(lapRows([{ lapNumber: 1, distanceMeters: 2000, durationSeconds: 600, averageSpeedMetersPerSecond: 3.33, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null }], records)[0].source).toBe('recorded')
    expect(lapRows([], records).map((row) => row.distanceMeters)).toEqual([1000, 1000])
    expect(lapRows([], [{ ...records[0], distanceMeters: null }, records[1]])).toEqual([])
  })

  it('calculates recorded lap pace from distance and duration when average speed is missing', () => {
    expect(lapPaceSecondsPerKm({ distanceMeters: 5000, durationSeconds: 1800, averageSpeedMetersPerSecond: null })).toBe(360)
    expect(lapPaceSecondsPerKm({ distanceMeters: null, durationSeconds: 1800, averageSpeedMetersPerSecond: null })).toBeNull()
  })

  it('derives descent only across consecutive available altitude measurements', () => {
    const records = [
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: 100, speedMetersPerSecond: 2, heartRate: 140, cadence: 80 },
      { elapsedSeconds: 10, distanceMeters: 20, latitude: null, longitude: null, altitudeMeters: 90, speedMetersPerSecond: 2, heartRate: 141, cadence: 80 },
      { elapsedSeconds: 20, distanceMeters: 40, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: 2, heartRate: 142, cadence: 80 },
      { elapsedSeconds: 30, distanceMeters: 60, latitude: null, longitude: null, altitudeMeters: 80, speedMetersPerSecond: 2, heartRate: 143, cadence: 80 },
    ]
    expect(descendedMeters(records)).toBe(10)
    expect(descendedMeters([records[0]])).toBeNull()
    expect(descendedMeters([records[0], { ...records[1], altitudeMeters: null }, records[3]])).toBeNull()
  })

  it('interpolates kilometer boundaries when deriving splits from sparse but monotonic samples', () => {
    const records = [
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: null, cadence: null },
      { elapsedSeconds: 1800, distanceMeters: 5000, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: null, cadence: null },
    ]
    expect(lapRows([], records).map(({ distanceMeters, durationSeconds }) => [distanceMeters, durationSeconds])).toEqual([[1000, 360], [1000, 360], [1000, 360], [1000, 360], [1000, 360]])
  })

  it('retains missing-value gaps when reducing chart data', () => {
    const records = Array.from({ length: 3001 }, (_, index) => ({ elapsedSeconds: index, distanceMeters: index, latitude: null, longitude: null, altitudeMeters: index, speedMetersPerSecond: 2, heartRate: index === 1 ? null : 140, cadence: index }))
    expect(chartSeries(records).heartRate.some(([, value]) => value === null)).toBe(true)
  })

  it('does not retain every record when an optional sensor is absent throughout', () => {
    const records = Array.from({ length: 30000 }, (_, index) => ({ elapsedSeconds: index, distanceMeters: index, latitude: null, longitude: null, altitudeMeters: index, speedMetersPerSecond: 2, heartRate: 140, cadence: null }))
    expect(chartSeries(records).cadence.length).toBeLessThanOrEqual(1502)
  })

  it('derives splits across stopped samples with unchanged cumulative distance', () => {
    const records = [
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: null, cadence: null },
      { elapsedSeconds: 1, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: null, cadence: null },
      { elapsedSeconds: 301, distanceMeters: 1000, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: null, cadence: null },
    ]
    expect(lapRows([], records).map(({ distanceMeters, durationSeconds }) => [distanceMeters, durationSeconds])).toEqual([[1000, 301]])
  })
})
