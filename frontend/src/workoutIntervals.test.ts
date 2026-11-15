import { describe, expect, it } from 'vitest'
import type { Lap, WorkoutStep } from './api'
import {
  createWorkoutIntervals,
  formatWorkoutTarget,
  normalizeWorkoutTarget,
  workoutTargetMarks,
  workoutZoomExtent,
  sortWorkoutIntervals,
  workoutLegend,
  workoutPhaseColor,
  workoutPhaseName,
  workoutPhaseAreas,
  workoutTooltipLabel,
  formatWorkoutDuration,
} from './workoutIntervals'

const step = (stepIndex: number, values: Partial<WorkoutStep> = {}): WorkoutStep => ({
  stepIndex,
  name: null,
  durationType: 'distance',
  plannedDurationSeconds: null,
  plannedDistanceMeters: null,
  repeatFromStepIndex: null,
  repeatCount: null,
  intensity: null,
  ...values,
})

const lap = (lapNumber: number, workoutStepIndex: number | null, values: Partial<Lap> = {}): Lap => ({
  lapNumber,
  durationSeconds: 60,
  distanceMeters: 1000,
  averageSpeedMetersPerSecond: null,
  averageHeartRate: 150,
  averageCadence: null,
  ascentMeters: null,
  descentMeters: null,
  workoutStepIndex,
  repetitionNumber: null,
  startElapsedSeconds: (lapNumber - 1) * 60,
  endElapsedSeconds: lapNumber * 60,
  startDistanceMeters: (lapNumber - 1) * 1000,
  endDistanceMeters: lapNumber * 1000,
  ...values,
})

describe('workout interval presentation', () => {
  it('maps FIT intensity labels, prefers the FIT name and uses step number only as last fallback', () => {
    const labels = ['active', 'rest', 'warmup', 'cooldown', 'recovery', 'interval', 'other']
      .map((intensity, stepIndex) => workoutPhaseName(step(stepIndex, { intensity })))

    expect(labels).toEqual(['Belastung', 'Pause', 'Einlaufen', 'Auslaufen', 'Erholung', 'Intervall', 'Sonstiges'])
    expect(workoutPhaseName(step(2, { name: '  1 km schnell  ', intensity: 'active' }))).toBe('1 km schnell')
    expect(workoutPhaseName(step(4, { intensity: null }))).toBe('Schritt 5')
  })

  it('keeps linked repeated laps in execution order and hides FIT repeat-control steps', () => {
    const steps = [
      step(0, { intensity: 'warmup' }),
      step(1, { intensity: 'active' }),
      step(2, { intensity: 'rest' }),
      step(3, { durationType: 'repeat_until_steps_cmplt', repeatFromStepIndex: 1, repeatCount: 3 }),
      step(4, { intensity: 'cooldown' }),
    ]
    const laps = [
      lap(4, 1, { repetitionNumber: 2, startElapsedSeconds: 180 }),
      lap(2, 1, { repetitionNumber: 1, startElapsedSeconds: 60 }),
      lap(1, 0, { startElapsedSeconds: 0 }),
      lap(3, 2, { repetitionNumber: 2, startElapsedSeconds: 120 }),
      lap(6, 3, { startElapsedSeconds: 300 }),
      lap(5, 4, { startElapsedSeconds: 240 }),
      lap(7, null, { startElapsedSeconds: 360 }),
    ]

    const result = createWorkoutIntervals(steps, laps)

    expect(result.intervals.map(({ lapNumber }) => lapNumber)).toEqual([1, 2, 3, 4, 5])
    expect(result.intervals.map(({ phaseName }) => phaseName)).toEqual([
      'Einlaufen', 'Belastung', 'Pause', 'Belastung', 'Auslaufen',
    ])
    expect(result.intervals.filter(({ repeatBlockCount }) => repeatBlockCount !== null)
      .map(({ repeatBlockCount }) => repeatBlockCount)).toEqual([3, 3, 3])
    expect(result.unmatchedStepCount).toBe(0)
  })

  it('orders actual executions by elapsed start time before lap number when available', () => {
    const result = createWorkoutIntervals(
      [step(0, { intensity: 'active' })],
      [
        lap(8, 0, { startElapsedSeconds: 60, endElapsedSeconds: 120 }),
        lap(2, 0, { startElapsedSeconds: 0, endElapsedSeconds: 60 }),
      ],
    )
    expect(result.intervals.map(({ lapNumber }) => lapNumber)).toEqual([2, 8])
  })

  it('numbers linked workout executions consecutively across unlinked laps', () => {
    const result = createWorkoutIntervals(
      [step(0, { intensity: 'active' })],
      [lap(1, 0), lap(2, null), lap(3, 0)],
    )
    expect(result.intervals.map(({ executionOrder }) => executionOrder)).toEqual([0, 1])
  })

  it('reports workout steps with missing executed laps without inventing interval rows', () => {
    const result = createWorkoutIntervals([
      step(0, { intensity: 'warmup' }),
      step(1, { intensity: 'active' }),
      step(2, { durationType: 'repeat_until_steps_cmplt', repeatFromStepIndex: 1, repeatCount: null }),
    ], [lap(1, 0)])

    expect(result.intervals).toHaveLength(1)
    expect(result.unmatchedStepCount).toBe(1)
  })

  it('sorts by selected values, keeps ties in execution order and leaves missing values last', () => {
    const rows = createWorkoutIntervals(
      [step(0, { intensity: 'active' }), step(1, { intensity: 'rest' }), step(2, { intensity: 'active' })],
      [
        lap(1, 0, { durationSeconds: 90, distanceMeters: 400, averageSpeedMetersPerSecond: 2 }),
        lap(2, 1, { durationSeconds: 60, distanceMeters: 800, averageSpeedMetersPerSecond: 4 }),
        lap(3, 2, { durationSeconds: 60, distanceMeters: 600, averageSpeedMetersPerSecond: 4 }),
        lap(4, 1, { durationSeconds: null, distanceMeters: null, averageSpeedMetersPerSecond: null }),
      ],
    ).intervals

    expect(sortWorkoutIntervals(rows, 'execution', 'asc').map(({ lapNumber }) => lapNumber)).toEqual([1, 2, 3, 4])
    expect(sortWorkoutIntervals(rows, 'duration', 'asc').map(({ lapNumber }) => lapNumber)).toEqual([2, 3, 1, 4])
    expect(sortWorkoutIntervals(rows, 'distance', 'desc').map(({ lapNumber }) => lapNumber)).toEqual([2, 3, 1, 4])
    expect(sortWorkoutIntervals(rows, 'pace', 'asc').map(({ lapNumber }) => lapNumber)).toEqual([2, 3, 1, 4])
    expect(sortWorkoutIntervals(rows, 'step', 'desc').map(({ lapNumber }) => lapNumber)).toEqual([3, 2, 4, 1])
  })

  it('uses FIT custom target values and zones only when they are present', () => {
    expect(formatWorkoutTarget(step(1, {
      targetType: 'heart_rate',
      targetZone: null,
      targetLow: 135,
      targetHigh: 155,
      targetUnit: 'bpm',
    }))).toBe('Herzfrequenz: 135–155 bpm')
    expect(formatWorkoutTarget(step(2, {
      targetType: 'power',
      targetZone: 3,
      targetLow: null,
      targetHigh: null,
      targetUnit: null,
    }))).toBe('Leistungszone 3')
    expect(formatWorkoutTarget(step(3))).toBeNull()
  })

  it('formats FIT speed targets as ascending pace without re-scaling decoded meters per second', () => {
    const target = normalizeWorkoutTarget(step(0, {
      targetType: 'speed',
      targetLow: 3.077,
      targetHigh: 3.175,
      targetUnit: 'm/s',
    }))

    expect(target).toMatchObject({
      label: 'Pace: 5:15–5:25 min/km',
      metric: 'pace',
    })
    expect(Math.round(target!.low!)).toBe(315)
    expect(Math.round(target!.high!)).toBe(325)
    expect(formatWorkoutTarget(step(0, {
      targetType: 'speed', targetLow: 3.077, targetHigh: 3.175, targetUnit: 'm/s',
    }))).toBe('Pace: 5:15–5:25 min/km')
  })

  it('rounds pace targets, sorts reversed speed bounds, and safely omits invalid speeds', () => {
    expect(normalizeWorkoutTarget(step(0, {
      targetType: 'speed', targetLow: 3.175, targetHigh: 3.077,
    }))?.label).toBe('Pace: 5:15–5:25 min/km')
    expect(normalizeWorkoutTarget(step(0, { targetType: 'speed', targetLow: 3.2 }))?.label)
      .toBe('Pace: 5:13 min/km')
    expect(normalizeWorkoutTarget(step(0, { targetType: 'speed', targetLow: 0, targetHigh: -1 }))).toBeNull()
    expect(normalizeWorkoutTarget(step(0, { targetType: 'speed', targetLow: Number.NaN, targetHigh: Number.POSITIVE_INFINITY }))).toBeNull()
    expect(normalizeWorkoutTarget(step(0, { targetType: 'speed', targetLow: 3, targetHigh: Number.NaN }))?.label)
      .toBe('Pace: 5:33 min/km')
  })

  it('formats absolute HR, power, and cadence targets while keeping unknown zone bounds unresolved', () => {
    expect(normalizeWorkoutTarget(step(0, { targetType: 'heart_rate', targetLow: 135, targetHigh: 155 })))
      .toEqual({ label: 'Herzfrequenz: 135–155 bpm', metric: 'heartRate', low: 135, high: 155 })
    expect(normalizeWorkoutTarget(step(0, { targetType: 'power', targetLow: 240, targetHigh: 280, targetUnit: 'W' })))
      .toEqual({ label: 'Leistung: 240–280 W', metric: 'power', low: 240, high: 280 })
    expect(normalizeWorkoutTarget(step(0, { targetType: 'cadence', targetLow: 80, targetHigh: 90 })))
      .toEqual({ label: 'Kadenz: 80–90 spm', metric: 'cadence', low: 80, high: 90 })
    expect(normalizeWorkoutTarget(step(0, { targetType: 'heart_rate', targetZone: 3 })))
      .toEqual({ label: 'Herzfrequenzzone 3', metric: 'heartRate', low: null, high: null })
    expect(normalizeWorkoutTarget(step(0, {
      targetType: 'heart_rate', targetZone: 3, targetLow: 140, targetHigh: 160, targetUnit: 'bpm',
    }))).toEqual({ label: 'Herzfrequenz: 140–160 bpm', metric: 'heartRate', low: 140, high: 160 })
    expect(normalizeWorkoutTarget(step(0, { targetType: 'power', targetLow: 70, targetHigh: 80, targetUnit: '% FTP' })))
      .toEqual({ label: 'Leistungsziel: 70–80 % FTP', metric: 'power', low: null, high: null })
  })

  it('formats FIT time and distance duration instructions', () => {
    expect(formatWorkoutTarget(step(0, {
      durationType: 'time', plannedDurationSeconds: 3665,
    }))).toBe('Dauer: 1:01:05')
    expect(formatWorkoutTarget(step(1, {
      durationType: 'time', plannedDurationSeconds: 90,
    }))).toBe('Dauer: 1:30')
    expect(formatWorkoutTarget(step(2, {
      durationType: 'distance', plannedDistanceMeters: 400,
    }))).toBe('Distanz: 400 m')
    expect(formatWorkoutTarget(step(3, {
      durationType: 'distance', plannedDistanceMeters: 2500,
    }))).toBe('Distanz: 2.50 km')
    expect(formatWorkoutTarget(step(4, {
      durationType: 'time',
      plannedDurationSeconds: 90,
      targetType: 'speed',
      targetLow: 3,
      targetHigh: 3.2,
      targetUnit: 'm/s',
    }))).toBe('Pace: 5:13–5:33 min/km · Dauer: 1:30')
  })

  it('keeps each resolved target mark inside its own interval on time and distance axes', () => {
    const intervals = createWorkoutIntervals([
      step(0, { targetType: 'speed', targetLow: 3, targetHigh: 4 }),
      step(1, { targetType: 'heart_rate', targetLow: 140, targetHigh: 155 }),
      step(2, { targetType: 'power', targetZone: 3 }),
      step(3, { targetType: 'cadence', targetLow: 85 }),
    ], [
      lap(1, 0, { startElapsedSeconds: 0, endElapsedSeconds: 60, startDistanceMeters: 0, endDistanceMeters: 200 }),
      lap(2, 1, { startElapsedSeconds: 60, endElapsedSeconds: 120, startDistanceMeters: 200, endDistanceMeters: 400 }),
      lap(3, 2, { startElapsedSeconds: 120, endElapsedSeconds: 180, startDistanceMeters: 400, endDistanceMeters: 600 }),
      lap(4, 3, { startElapsedSeconds: 180, endElapsedSeconds: 240, startDistanceMeters: 600, endDistanceMeters: 800 }),
    ]).intervals

    expect(workoutTargetMarks(intervals, 'time', 'pace')).toEqual([
      { id: intervals[0].id, start: 0, end: 60, low: 250, high: 1000 / 3 },
    ])
    expect(workoutTargetMarks(intervals, 'distance', 'heartRate')).toEqual([
      { id: intervals[1].id, start: 200, end: 400, low: 140, high: 155 },
    ])
    expect(workoutTargetMarks(intervals, 'time', 'power')).toEqual([])
    expect(workoutTargetMarks(intervals, 'distance', 'cadence')).toEqual([
      { id: intervals[3].id, start: 600, end: 800, low: 85, high: 85 },
    ])
  })

  it('adds a small zoom margin and clamps it to the selected axis domain', () => {
    const interval = createWorkoutIntervals([step(0)], [
      lap(1, 0, { startElapsedSeconds: 100, endElapsedSeconds: 200, startDistanceMeters: 500, endDistanceMeters: 1500 }),
    ]).intervals[0]

    expect(workoutZoomExtent(interval, 'time', { start: 0, end: 300 })).toEqual({ start: 95, end: 205 })
    expect(workoutZoomExtent(interval, 'distance', { start: 600, end: 1400 })).toEqual({ start: 600, end: 1400 })
    expect(workoutZoomExtent(interval, 'time', { start: null, end: 300 })).toBeNull()
  })

  it('gives executed intervals stable IDs and groups only contiguous laps with explicit same repetition', () => {
    const result = createWorkoutIntervals([step(2, { intensity: 'active' })], [
      lap(1, 2, { durationSeconds: 30, averageHeartRate: 130, repetitionNumber: 1, startElapsedSeconds: 0, endElapsedSeconds: 30, startDistanceMeters: 0, endDistanceMeters: 100 }),
      lap(2, 2, { durationSeconds: 60, averageHeartRate: 170, repetitionNumber: 1, startElapsedSeconds: 30, endElapsedSeconds: 60, startDistanceMeters: 100, endDistanceMeters: 200 }),
      lap(3, 2, { repetitionNumber: 2, startElapsedSeconds: 60, endElapsedSeconds: 90, startDistanceMeters: 200, endDistanceMeters: 300 }),
    ])

    expect(result.intervals).toHaveLength(2)
    expect(result.intervals.map(({ id, lapNumbers, startElapsedSeconds, endElapsedSeconds, averageHeartRate }) =>
      ({ id, lapNumbers, startElapsedSeconds, endElapsedSeconds, averageHeartRate }))).toEqual([
      { id: 'step:2:lap:1', lapNumbers: [1, 2], startElapsedSeconds: 0, endElapsedSeconds: 60, averageHeartRate: 156.66666666666666 },
      { id: 'step:2:lap:3', lapNumbers: [3], startElapsedSeconds: 60, endElapsedSeconds: 90, averageHeartRate: 150 },
    ])
  })

  it('keeps separated executions unique even when FIT repeats the same step and repetition number', () => {
    const result = createWorkoutIntervals([step(1), step(2)], [
      lap(1, 2, { repetitionNumber: 1 }),
      lap(2, 1, { repetitionNumber: 1 }),
      lap(3, 2, { repetitionNumber: 1 }),
    ])

    expect(result.intervals[0].id).not.toBe(result.intervals[2].id)
    expect(new Set(result.intervals.map(({ id }) => id)).size).toBe(result.intervals.length)
  })

  it('labels tooltip phase and reliable repeat position', () => {
    const [interval] = createWorkoutIntervals(
      [step(0, { intensity: 'active' }), step(1, { durationType: 'repeat_until_steps_cmplt', repeatFromStepIndex: 0, repeatCount: 5 })],
      [lap(1, 0, { repetitionNumber: 2 })],
    ).intervals
    expect(workoutTooltipLabel(interval)).toBe('Belastung · Wiederholung 2 von 5')
  })

  it('formats executed interval duration with seconds rather than rounded minutes', () => {
    expect(formatWorkoutDuration(290)).toBe('4:50')
    expect(formatWorkoutDuration(3665)).toBe('1:01:05')
    expect(formatWorkoutDuration(null)).toBe('–')
  })

  it('provides one color per detected phase and positions transparent chart areas on either axis', () => {
    const result = createWorkoutIntervals([
      step(0, { intensity: 'warmup' }),
      step(1, { intensity: 'active' }),
    ], [
      lap(1, 0, { startElapsedSeconds: 0, endElapsedSeconds: 120, startDistanceMeters: 0, endDistanceMeters: 500 }),
      lap(2, 1, { startElapsedSeconds: 120, endElapsedSeconds: 240, startDistanceMeters: 500, endDistanceMeters: 1000 }),
    ])
    const legend = workoutLegend(result.intervals)

    expect(legend).toEqual([
      { label: 'Einlaufen', color: '#DCFCE7' },
      { label: 'Belastung / Intervall', color: '#FFEDD5' },
    ])
    expect(workoutPhaseColor('recovery')).toBe('#DBEAFE')
    expect(workoutPhaseColor('rest')).toBe('#F3F4F6')
    expect(workoutPhaseColor('cooldown')).toBe('#BFDBFE')
    expect(workoutPhaseColor(null)).toBe('#E5E7EB')
    expect(workoutPhaseAreas(result.intervals, 'distance')).toEqual([
      { start: 0, end: 500, color: 'rgba(220, 252, 231, 0.55)' },
      { start: 500, end: 1000, color: 'rgba(255, 237, 213, 0.55)' },
    ])
    expect(workoutPhaseAreas(result.intervals, 'time').map(({ start, end }) => [start, end]))
      .toEqual([[0, 120], [120, 240]])
  })
})
