import type { Lap, WorkoutStep } from './api'

export type WorkoutSort = 'execution' | 'step' | 'duration' | 'distance' | 'pace'
export type WorkoutDirection = 'asc' | 'desc'
export type WorkoutAxis = 'distance' | 'time'

type PhaseKind = 'warmup' | 'active' | 'interval' | 'rest' | 'recovery' | 'cooldown' | 'other' | 'unknown'

export type WorkoutInterval = {
  id: string
  executionOrder: number
  lapNumber: number
  lapNumbers: number[]
  stepIndex: number
  phaseName: string
  intensity: string | null
  phaseKind: PhaseKind
  repetitionNumber: number | null
  repeatBlockCount: number | null
  distanceMeters: number | null
  durationSeconds: number | null
  averagePaceSecondsPerKm: number | null
  averageHeartRate: number | null
  startElapsedSeconds: number | null
  endElapsedSeconds: number | null
  startDistanceMeters: number | null
  endDistanceMeters: number | null
  target: WorkoutStep
}

const intensityLabels: Record<Exclude<PhaseKind, 'unknown'>, string> = {
  warmup: 'Einlaufen',
  active: 'Belastung',
  interval: 'Intervall',
  rest: 'Pause',
  recovery: 'Erholung',
  cooldown: 'Auslaufen',
  other: 'Sonstiges',
}

const phaseColors: Record<PhaseKind, string> = {
  warmup: '#DCFCE7',
  active: '#FFEDD5',
  interval: '#FFEDD5',
  rest: '#F3F4F6',
  recovery: '#DBEAFE',
  cooldown: '#BFDBFE',
  other: '#E5E7EB',
  unknown: '#E5E7EB',
}

const legendItems: Array<{ kinds: PhaseKind[]; label: string; color: string }> = [
  { kinds: ['warmup'], label: 'Einlaufen', color: phaseColors.warmup },
  { kinds: ['active', 'interval'], label: 'Belastung / Intervall', color: phaseColors.active },
  { kinds: ['recovery'], label: 'Erholung', color: phaseColors.recovery },
  { kinds: ['rest'], label: 'Pause', color: phaseColors.rest },
  { kinds: ['cooldown'], label: 'Auslaufen', color: phaseColors.cooldown },
  { kinds: ['other', 'unknown'], label: 'Unbekannt / Sonstiges', color: phaseColors.unknown },
]

function phaseKind(intensity: string | null): PhaseKind {
  switch (intensity?.toLowerCase()) {
    case 'warmup': return 'warmup'
    case 'active': return 'active'
    case 'rest': return 'rest'
    case 'recovery': return 'recovery'
    case 'cooldown': return 'cooldown'
    case 'interval': return 'interval'
    case 'other': return 'other'
    default: return 'unknown'
  }
}

export function isRepeatControlStep(step: WorkoutStep): boolean {
  return step.durationType?.toLowerCase().startsWith('repeat_until_') ?? false
}

export function workoutPhaseName(step: WorkoutStep): string {
  const name = step.name?.trim()
  if (name) return name
  const kind = phaseKind(step.intensity)
  return kind === 'unknown' ? `Schritt ${step.stepIndex + 1}` : intensityLabels[kind]
}

export function workoutPhaseColor(intensity: string | null): string {
  return phaseColors[phaseKind(intensity)]
}

export type NormalizedWorkoutTarget = {
  label: string
  metric: 'pace' | 'heartRate' | 'power' | 'cadence' | null
  low: number | null
  high: number | null
}

function targetNumber(value: number | null | undefined): number | null {
  return value !== null && value !== undefined && Number.isFinite(value) ? value : null
}

function targetRange(low: number | null, high: number | null): [number | null, number | null] {
  if (low !== null && high !== null && low > high) return [high, low]
  return [low, high]
}

function targetValueLabel(low: number | null, high: number | null): string {
  return low !== null && high !== null && low !== high ? `${low}–${high}` : `${low ?? high}`
}

function paceLabel(secondsPerKm: number): string {
  const seconds = Math.round(secondsPerKm)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function durationLabel(seconds: number): string {
  return formatWorkoutDuration(seconds)
}

function distanceLabel(meters: number): string {
  return meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`
}

export function normalizeWorkoutTarget(step: WorkoutStep): NormalizedWorkoutTarget | null {
  const type = step.targetType?.toLowerCase()
  if (!type) {
    if (step.durationType?.toLowerCase() === 'time' && targetNumber(step.plannedDurationSeconds) !== null) {
      return { label: `Dauer: ${durationLabel(step.plannedDurationSeconds!)}`, metric: null, low: null, high: null }
    }
    if (step.durationType?.toLowerCase() === 'distance' && targetNumber(step.plannedDistanceMeters) !== null) {
      return { label: `Distanz: ${distanceLabel(step.plannedDistanceMeters!)}`, metric: null, low: null, high: null }
    }
    return null
  }

  const kind = type.startsWith('heart_rate') ? 'heartRate'
    : type.startsWith('power') ? 'power'
      : type.startsWith('speed') ? 'pace'
        : type === 'cadence' ? 'cadence' : null
  if (!kind) return null

  const hasZone = step.targetZone !== null && step.targetZone !== undefined && step.targetZone > 0
  const rawLow = targetNumber(step.targetLow)
  const rawHigh = targetNumber(step.targetHigh)
  const hasKnownBounds = rawLow !== null && rawHigh !== null && (kind !== 'pace' || (rawLow > 0 && rawHigh > 0))
  if (hasZone && !hasKnownBounds) {
    const label = kind === 'heartRate' ? 'Herzfrequenzzone'
      : kind === 'power' ? 'Leistungszone'
        : kind === 'pace' ? 'Geschwindigkeitszone' : 'Kadenzzone'
    return { label: `${label} ${step.targetZone}`, metric: kind, low: null, high: null }
  }

  let low = rawLow
  let high = rawHigh
  if (kind === 'pace') {
    if (step.targetUnit && step.targetUnit !== 'm/s') return null
    low = low !== null && low > 0 ? 1000 / low : null
    high = high !== null && high > 0 ? 1000 / high : null
    ;[low, high] = targetRange(low, high)
    if (low === null && high === null) return null
    const pace = low !== null && high !== null && Math.round(low) !== Math.round(high)
      ? `${paceLabel(low)}–${paceLabel(high)}`
      : paceLabel(low ?? high!)
    return { label: `Pace: ${pace} min/km`, metric: kind, low, high }
  }

  const expectedUnit = kind === 'heartRate' ? 'bpm' : kind === 'power' ? 'W' : 'spm'
  const unit = kind === 'cadence' && (step.targetUnit === 'steps/min' || step.targetUnit === 'spm')
    ? 'spm' : step.targetUnit ?? expectedUnit
  const prefix = kind === 'heartRate' ? 'Herzfrequenz' : kind === 'power' ? 'Leistung' : 'Kadenz'
  if (low === null && high === null) return null
  ;[low, high] = targetRange(low, high)
  const absolute = unit === expectedUnit || (kind === 'cadence' && unit === 'steps/min')
  return {
    label: `${absolute ? prefix : `${kind === 'power' ? 'Leistungs' : prefix}ziel`}: ${targetValueLabel(low, high)} ${unit}`,
    metric: kind,
    low: absolute ? low : null,
    high: absolute ? high : null,
  }
}

export function formatWorkoutTarget(step: WorkoutStep): string | null {
  const target = normalizeWorkoutTarget(step)
  if (!target) return null
  const durationType = step.durationType?.toLowerCase()
  const duration = step.plannedDurationSeconds
  const distance = step.plannedDistanceMeters
  const executionLimit = step.targetType && durationType === 'time'
    && targetNumber(duration) !== null && duration! >= 0
    ? `Dauer: ${durationLabel(duration!)}`
    : step.targetType && durationType === 'distance'
      && targetNumber(distance) !== null && distance! > 0
      ? `Distanz: ${distanceLabel(distance!)}`
      : null
  return executionLimit ? `${target.label} · ${executionLimit}` : target.label
}

export function workoutTooltipLabel(interval: WorkoutInterval): string {
  return `${interval.phaseName}${interval.repetitionNumber === null ? '' : ` · Wiederholung ${interval.repetitionNumber}${interval.repeatBlockCount === null ? '' : ` von ${interval.repeatBlockCount}`}`}`
}

export function formatWorkoutDuration(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return '–'
  const total = Math.round(seconds)
  const minutes = Math.floor(total / 60)
  return `${Math.floor(minutes / 60) ? `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}` : minutes}:${String(total % 60).padStart(2, '0')}`
}

export function createWorkoutIntervals(steps: WorkoutStep[], laps: Lap[]) {
  const executableSteps = steps.filter((step) => !isRepeatControlStep(step))
  const stepByIndex = new Map(executableSteps.map((step) => [step.stepIndex, step]))
  const repeatBlockCounts = new Map<number, number>()
  for (const control of steps.filter(isRepeatControlStep)) {
    const from = control.repeatFromStepIndex
    const count = control.repeatCount
    if (from === null || count === null || !Number.isInteger(from) || !Number.isInteger(count)
        || from < 0 || count < 1 || control.stepIndex <= from) continue
    for (let index = from; index < control.stepIndex; index++) {
      if (stepByIndex.has(index)) repeatBlockCounts.set(index, count)
    }
  }

  const orderedLaps = [...laps].sort((a, b) => {
    const startOrder = a.startElapsedSeconds !== null && a.startElapsedSeconds !== undefined
      && b.startElapsedSeconds !== null && b.startElapsedSeconds !== undefined
      ? a.startElapsedSeconds - b.startElapsedSeconds : 0
    return startOrder || a.lapNumber - b.lapNumber
  })
  const intervals = orderedLaps.flatMap((lap) => {
    const step = lap.workoutStepIndex === null || lap.workoutStepIndex === undefined
      ? undefined : stepByIndex.get(lap.workoutStepIndex)
    if (!step) return []
    const averagePaceSecondsPerKm = lap.averageSpeedMetersPerSecond !== null
      && Number.isFinite(lap.averageSpeedMetersPerSecond) && lap.averageSpeedMetersPerSecond > 0
      ? 1000 / lap.averageSpeedMetersPerSecond
      : lap.distanceMeters !== null && lap.durationSeconds !== null
        && Number.isFinite(lap.distanceMeters) && Number.isFinite(lap.durationSeconds)
        && lap.distanceMeters > 0 && lap.durationSeconds > 0
        ? 1000 * lap.durationSeconds / lap.distanceMeters : null
    return [{
      id: `step:${step.stepIndex}:lap:${lap.lapNumber}`,
      executionOrder: 0,
      lapNumber: lap.lapNumber,
      lapNumbers: [lap.lapNumber],
      stepIndex: step.stepIndex,
      phaseName: workoutPhaseName(step),
      intensity: step.intensity,
      phaseKind: phaseKind(step.intensity),
      repetitionNumber: lap.repetitionNumber ?? null,
      repeatBlockCount: repeatBlockCounts.get(step.stepIndex) ?? null,
      distanceMeters: lap.distanceMeters,
      durationSeconds: lap.durationSeconds,
      averagePaceSecondsPerKm,
      averageHeartRate: lap.averageHeartRate,
      startElapsedSeconds: lap.startElapsedSeconds ?? null,
      endElapsedSeconds: lap.endElapsedSeconds ?? null,
      startDistanceMeters: lap.startDistanceMeters ?? null,
      endDistanceMeters: lap.endDistanceMeters ?? null,
      target: step,
    }]
  })
  const grouped: typeof intervals = []
  for (const interval of intervals) {
    const previous = grouped.at(-1)
    if (previous && interval.repetitionNumber !== null
        && previous.stepIndex === interval.stepIndex
        && previous.repetitionNumber === interval.repetitionNumber
        && interval.lapNumber === previous.lapNumbers.at(-1)! + 1) {
      const previousDuration = previous.durationSeconds
      const intervalDuration = interval.durationSeconds
      previous.averageHeartRate = previous.averageHeartRate !== null && interval.averageHeartRate !== null
          && previousDuration !== null && intervalDuration !== null && previousDuration > 0 && intervalDuration > 0
        ? (previous.averageHeartRate * previousDuration + interval.averageHeartRate * intervalDuration)
          / (previousDuration + intervalDuration)
        : null
      previous.lapNumbers.push(...interval.lapNumbers)
      previous.distanceMeters = previous.distanceMeters === null || interval.distanceMeters === null
        ? null : previous.distanceMeters + interval.distanceMeters
      previous.durationSeconds = previous.durationSeconds === null || interval.durationSeconds === null
        ? null : previous.durationSeconds + interval.durationSeconds
      previous.averagePaceSecondsPerKm = previous.distanceMeters && previous.durationSeconds
        ? 1000 * previous.durationSeconds / previous.distanceMeters : null
      previous.startElapsedSeconds = previous.startElapsedSeconds ?? interval.startElapsedSeconds
      previous.endElapsedSeconds = interval.endElapsedSeconds ?? previous.endElapsedSeconds
      previous.startDistanceMeters = previous.startDistanceMeters ?? interval.startDistanceMeters
      previous.endDistanceMeters = interval.endDistanceMeters ?? previous.endDistanceMeters
    } else {
      grouped.push(interval)
    }
  }
  const orderedIntervals = grouped.map((interval, executionOrder) => ({ ...interval, executionOrder }))
  const executedSteps = new Set(orderedIntervals.map(({ stepIndex }) => stepIndex))
  return {
    intervals: orderedIntervals,
    unmatchedStepCount: executableSteps.filter(({ stepIndex }) => !executedSteps.has(stepIndex)).length,
  }
}

export function sortWorkoutIntervals(
  intervals: WorkoutInterval[],
  sort: WorkoutSort,
  direction: WorkoutDirection,
): WorkoutInterval[] {
  const value = (interval: WorkoutInterval): number | null => {
    switch (sort) {
      case 'step': return interval.stepIndex
      case 'duration': return interval.durationSeconds
      case 'distance': return interval.distanceMeters
      case 'pace': return interval.averagePaceSecondsPerKm
      case 'execution': return interval.executionOrder
    }
  }
  return [...intervals].sort((a, b) => {
    const left = value(a)
    const right = value(b)
    if (left === null || right === null) return left === right ? a.executionOrder - b.executionOrder : left === null ? 1 : -1
    const order = (left - right) * (direction === 'asc' ? 1 : -1)
    return order || a.executionOrder - b.executionOrder
  })
}

export function workoutLegend(intervals: WorkoutInterval[]) {
  const kinds = new Set(intervals.map(({ phaseKind }) => phaseKind))
  return legendItems.filter(({ kinds: itemKinds }) => itemKinds.some((kind) => kinds.has(kind)))
    .map(({ label, color }) => ({ label, color }))
}

function rgba(hex: string): string {
  const red = Number.parseInt(hex.slice(1, 3), 16)
  const green = Number.parseInt(hex.slice(3, 5), 16)
  const blue = Number.parseInt(hex.slice(5, 7), 16)
  return `rgba(${red}, ${green}, ${blue}, 0.55)`
}

export function workoutPhaseAreas(intervals: WorkoutInterval[], axis: WorkoutAxis) {
  return intervals.flatMap((interval) => {
    const start = axis === 'distance' ? interval.startDistanceMeters : interval.startElapsedSeconds
    const end = axis === 'distance' ? interval.endDistanceMeters : interval.endElapsedSeconds
    return start !== null && end !== null && Number.isFinite(start) && Number.isFinite(end) && end > start
      ? [{ start, end, color: rgba(phaseColors[interval.phaseKind]) }]
      : []
  })
}

export type WorkoutMetric = NonNullable<NormalizedWorkoutTarget['metric']>

export function workoutTargetMarks(
  intervals: WorkoutInterval[],
  axis: WorkoutAxis,
  metric: WorkoutMetric,
) {
  return intervals.flatMap((interval) => {
    const target = normalizeWorkoutTarget(interval.target)
    const start = axis === 'distance' ? interval.startDistanceMeters : interval.startElapsedSeconds
    const end = axis === 'distance' ? interval.endDistanceMeters : interval.endElapsedSeconds
    if (!target || target.metric !== metric || start === null || end === null
        || !Number.isFinite(start) || !Number.isFinite(end) || end <= start
        || (target.low === null && target.high === null)) return []
    return [{
      id: interval.id,
      start,
      end,
      low: target.low ?? target.high!,
      high: target.high ?? target.low!,
    }]
  })
}

export function workoutZoomExtent(
  interval: WorkoutInterval,
  axis: WorkoutAxis,
  domain: { start: number | null; end: number | null },
): { start: number; end: number } | null {
  const start = axis === 'distance' ? interval.startDistanceMeters : interval.startElapsedSeconds
  const end = axis === 'distance' ? interval.endDistanceMeters : interval.endElapsedSeconds
  if (start === null || end === null || domain.start === null || domain.end === null
      || !Number.isFinite(start) || !Number.isFinite(end)
      || !Number.isFinite(domain.start) || !Number.isFinite(domain.end)
      || end <= start || domain.end <= domain.start) return null
  const margin = (end - start) * 0.05
  return {
    start: Math.max(domain.start, start - margin),
    end: Math.min(domain.end, end + margin),
  }
}
