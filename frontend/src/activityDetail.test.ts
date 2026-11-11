import { describe, expect, it } from 'vitest'
import type { ActivityDetail } from './api'
import { chartSeries, createTrainingSummary, descendedMeters, displayCadence, formatPace, lapPaceSecondsPerKm, lapRows, paceAxis, serializeTrainingSummaryJson, serializeTrainingSummaryYaml } from './activityDetail'

const trainingDetail: ActivityDetail = {
  activity: { id: 7, sport: 'running', subSport: null, startTime: '2026-11-10T23:30:00-02:00', elapsedSeconds: 3630.4, distanceMeters: 10039, averageHeartRate: 148.7, ascentMeters: 64.6 },
  movingSeconds: 3570.6,
  averageSpeedMetersPerSecond: null,
  maximumHeartRate: 172.6,
  averageCadence: 84,
  calories: 500,
  garminTrainingMetrics: { aerobicTrainingEffect: 3.44, anaerobicTrainingEffect: 0, trainingLoad: 126.25 },
  records: [],
  laps: [],
}

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

  it('removes a very slow startup sample and keeps following running pace', () => {
    const series = chartSeries([
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: 100, speedMetersPerSecond: null, heartRate: 130, cadence: 70 },
      { elapsedSeconds: 253.02, distanceMeters: 100, latitude: null, longitude: null, altitudeMeters: 101, speedMetersPerSecond: null, heartRate: 132, cadence: 72 },
      { elapsedSeconds: 853.02, distanceMeters: 1600, latitude: null, longitude: null, altitudeMeters: 105, speedMetersPerSecond: null, heartRate: 145, cadence: 82 },
      { elapsedSeconds: 1453.02, distanceMeters: 3100, latitude: null, longitude: null, altitudeMeters: 110, speedMetersPerSecond: null, heartRate: 150, cadence: 84 },
    ])
    expect(series.pace.map(([, pace]) => pace === null)).toEqual([true, true, false, false])
    expect(series.pace[2][1]).toBeCloseTo(400)
    expect(series.pace[3][1]).toBeCloseTo(400)
    expect(series.heartRate.map(([, value]) => value)).toEqual([130, 132, 145, 150])
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

describe('training summary export', () => {
  it('builds a compact summary with rounded units, total-time pace, local date, and single cadence conversion', () => {
    expect(createTrainingSummary(trainingDetail)).toEqual({
      schema_version: 1,
      sport: 'running',
      date: new Date(trainingDetail.activity.startTime).toLocaleDateString('en-CA'),
      distance_km: 10.04,
      elapsed_sec: 3630,
      moving_sec: 3571,
      avg_pace_sec_per_km: 362,
      heart_rate_bpm: { avg: 149, max: 173 },
      cadence_steps_per_min: 168,
      ascent_m: 65,
      garmin: { aerobic_training_effect: 3.4, anaerobic_training_effect: 0, training_load: 126.25 },
    })
  })

  it('omits missing values and preserves measured zeroes', () => {
    const detail = { ...trainingDetail, activity: { ...trainingDetail.activity, distanceMeters: null, elapsedSeconds: null, averageHeartRate: null, ascentMeters: null }, movingSeconds: null, maximumHeartRate: null, averageCadence: null, garminTrainingMetrics: { aerobicTrainingEffect: null, anaerobicTrainingEffect: 0, trainingLoad: null } }
    expect(createTrainingSummary(detail)).toEqual({
      schema_version: 1,
      sport: 'running',
      date: new Date(detail.activity.startTime).toLocaleDateString('en-CA'),
      garmin: { anaerobic_training_effect: 0 },
    })
  })

  it('does not calculate pace for a zero or invalid distance', () => {
    expect(createTrainingSummary({ ...trainingDetail, activity: { ...trainingDetail.activity, distanceMeters: 0 } })).not.toHaveProperty('avg_pace_sec_per_km')
    expect(createTrainingSummary({ ...trainingDetail, activity: { ...trainingDetail.activity, distanceMeters: Number.NaN } })).not.toHaveProperty('distance_km')
  })

  it('exports recorded lap distance as recorded and never invents derived split heart rate', () => {
    const recorded = { lapNumber: 3, distanceMeters: 995, durationSeconds: 360, averageSpeedMetersPerSecond: null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null }
    const recordedSummary = createTrainingSummary({ ...trainingDetail, laps: [recorded] }, true)
    expect(recordedSummary.splits).toEqual([{ lap_number: 3, distance_km: 1, duration_sec: 360, avg_pace_sec_per_km: 362, source: 'recorded' }])

    const records = [
      { elapsedSeconds: 0, distanceMeters: 0, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: 150, cadence: null },
      { elapsedSeconds: 7560, distanceMeters: 21000, latitude: null, longitude: null, altitudeMeters: null, speedMetersPerSecond: null, heartRate: 160, cadence: null },
    ]
    const derived = createTrainingSummary({ ...trainingDetail, laps: [], records }, true).splits!
    expect(derived).toHaveLength(21)
    expect(derived.at(-1)).toEqual({ lap_number: 21, distance_km: 1, duration_sec: 360, avg_pace_sec_per_km: 360, source: 'derived' })
    expect(derived.every((split) => !('avg_heart_rate_bpm' in split))).toBe(true)
  })

  it('omits splits when none can be derived and keeps disabled splits out', () => {
    expect(createTrainingSummary({ ...trainingDetail, laps: [] }, true)).not.toHaveProperty('splits')
    expect(createTrainingSummary({ ...trainingDetail, laps: [{ lapNumber: 1, distanceMeters: 1000, durationSeconds: 360, averageSpeedMetersPerSecond: null, averageHeartRate: 150, averageCadence: null, ascentMeters: null, descentMeters: null }] })).not.toHaveProperty('splits')
  })

  it('serializes the same compact data as valid JSON and readable YAML without sensitive fields', () => {
    const summary = createTrainingSummary(trainingDetail)
    const json = serializeTrainingSummaryJson(summary)
    const yaml = serializeTrainingSummaryYaml(summary)
    expect(JSON.parse(json)).toEqual(summary)
    expect(yaml).toContain('schema_version: 1\nsport: "running"')
    expect(yaml).toContain('heart_rate_bpm:\n  avg: 149\n  max: 173')
    expect(json + yaml).not.toMatch(/latitude|longitude|records|api[_-]?key|file[_-]?path/i)
    expect(json + yaml).not.toMatch(/NaN|Infinity/)
  })
})
