import { describe, expect, it } from 'vitest'
import type { Lap, WorkoutStep } from './api'
import {
  createWorkoutIntervals,
  formatWorkoutTarget,
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
    }))).toBe('135–155 bpm')
    expect(formatWorkoutTarget(step(2, {
      targetType: 'power',
      targetZone: 3,
      targetLow: null,
      targetHigh: null,
      targetUnit: null,
    }))).toBe('Leistungszone 3')
    expect(formatWorkoutTarget(step(3))).toBeNull()
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
