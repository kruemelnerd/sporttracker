import type { Lap, WorkoutStep } from './api'

export type WorkoutSort = 'execution' | 'step' | 'duration' | 'distance' | 'pace'
export type WorkoutDirection = 'asc' | 'desc'
export type WorkoutAxis = 'distance' | 'time'

type PhaseKind = 'warmup' | 'active' | 'interval' | 'rest' | 'recovery' | 'cooldown' | 'other' | 'unknown'

export type WorkoutInterval = {
  executionOrder: number
  lapNumber: number
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

export function formatWorkoutTarget(step: WorkoutStep): string | null {
  const type = step.targetType?.toLowerCase()
  if (!type) return null
  if (step.targetZone !== null && step.targetZone !== undefined && step.targetZone > 0) {
    const label = type === 'heart_rate' ? 'Herzfrequenzzone'
      : type === 'power' ? 'Leistungszone'
        : type === 'speed' ? 'Geschwindigkeitszone'
          : type === 'cadence' ? 'Kadenzzone' : null
    if (label) return `${label} ${step.targetZone}`
  }
  const low = Number.isFinite(step.targetLow) ? step.targetLow! : null
  const high = Number.isFinite(step.targetHigh) ? step.targetHigh! : null
  if (low === null && high === null) return null
  const range = low !== null && high !== null ? `${low}–${high}` : `${low ?? high}`
  if (type === 'heart_rate') return `${range} ${step.targetUnit ?? 'bpm'}`
  if (type === 'power') return `${range} ${step.targetUnit ?? 'W'}`
  if (type === 'cadence') return `${range} ${step.targetUnit ?? 'steps/min'}`
  if (type === 'speed') return `${range} ${step.targetUnit ?? 'm/s'}`
  return null
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
      executionOrder: 0,
      lapNumber: lap.lapNumber,
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
  }).map((interval, executionOrder) => ({ ...interval, executionOrder }))
  const executedSteps = new Set(intervals.map(({ stepIndex }) => stepIndex))
  return {
    intervals,
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
