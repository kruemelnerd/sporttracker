import type { ActivityRecord, Lap } from './api'

const MAX_CHART_PACE_SECONDS_PER_KM = 17 * 60

export function formatPace(secondsPerKm: number | null): string {
  if (secondsPerKm === null || !Number.isFinite(secondsPerKm) || secondsPerKm <= 0) return '–'
  const seconds = Math.round(secondsPerKm)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')} min/km`
}

export function descendedMeters(records: ActivityRecord[]): number | null {
  let total = 0
  let previous: number | null = null
  let pairs = 0
  for (const record of records) {
    if (record.altitudeMeters === null) { previous = null; continue }
    if (previous !== null) { total += Math.max(0, previous - record.altitudeMeters); pairs++ }
    previous = record.altitudeMeters
  }
  return pairs ? total : null
}

export function paceAxis(value: number): string {
  return formatPace(value)
}

export function displayCadence(value: number | null): string {
  return value === null ? '–' : `${value * 2} steps/min`
}

export function chartSeries(records: ActivityRecord[]) {
  const ordered = records.filter((record) => record.distanceMeters !== null)
  const stride = Math.max(1, Math.ceil(ordered.length / 1500))
  const indices = new Set<number>([0, ordered.length - 1])
  for (let index = 0; index < ordered.length; index += stride) indices.add(index)
  const metrics = ['speedMetersPerSecond', 'heartRate', 'altitudeMeters', 'cadence'] as const
  for (const metric of metrics) {
    for (let index = 0; index < ordered.length; index++) {
      if ((ordered[index][metric] === null) !== (ordered[index - 1]?.[metric] === null)) indices.add(index)
    }
  }
  const reduced = ordered.filter((_, index) => indices.has(index))
  const pace = ordered.map((record, index) => {
    let secondsPerKm: number | null = null
    if (record.speedMetersPerSecond !== null && Number.isFinite(record.speedMetersPerSecond) && record.speedMetersPerSecond > 0) {
      secondsPerKm = 1000 / record.speedMetersPerSecond
    } else {
      const previous = ordered[index - 1]
      if (previous && record.distanceMeters !== null && previous.distanceMeters !== null && record.elapsedSeconds !== null && previous.elapsedSeconds !== null) {
        const distance = record.distanceMeters - previous.distanceMeters
        const elapsed = record.elapsedSeconds - previous.elapsedSeconds
        if (distance > 0 && elapsed > 0) secondsPerKm = 1000 * elapsed / distance
      }
    }
    return secondsPerKm !== null && Number.isFinite(secondsPerKm) && secondsPerKm <= MAX_CHART_PACE_SECONDS_PER_KM ? secondsPerKm : null
  })
  const reducedPace = [...indices].sort((a, b) => a - b).map((index) => pace[index])
  return {
    pace: reduced.map((record, index) => [record.distanceMeters!, reducedPace[index]] as [number, number | null]),
    heartRate: reduced.map((record) => [record.distanceMeters!, record.heartRate] as [number, number | null]),
    elevation: reduced.map((record) => [record.distanceMeters!, record.altitudeMeters] as [number, number | null]),
    cadence: reduced.map((record) => [record.distanceMeters!, record.cadence] as [number, number | null]),
  }
}

export type LapRow = Lap & { source: 'recorded' | 'derived' }

export function lapPaceSecondsPerKm(lap: Pick<Lap, 'distanceMeters' | 'durationSeconds' | 'averageSpeedMetersPerSecond'>): number | null {
  if (lap.averageSpeedMetersPerSecond !== null && Number.isFinite(lap.averageSpeedMetersPerSecond) && lap.averageSpeedMetersPerSecond > 0) return 1000 / lap.averageSpeedMetersPerSecond
  return lap.distanceMeters !== null && lap.durationSeconds !== null && Number.isFinite(lap.distanceMeters) && Number.isFinite(lap.durationSeconds) && lap.distanceMeters > 0 && lap.durationSeconds > 0
    ? 1000 * lap.durationSeconds / lap.distanceMeters
    : null
}

export function lapRows(laps: Lap[], records: ActivityRecord[]): LapRow[] {
  if (laps.length) return laps.map((lap) => ({ ...lap, source: 'recorded' }))
  if (records.length < 2 || records.some((record) => record.distanceMeters === null || record.elapsedSeconds === null)) return []
  const ordered = [...records].sort((a, b) => a.distanceMeters! - b.distanceMeters!)
  if (ordered.some((record, index) => index > 0 && (record.distanceMeters! < ordered[index - 1].distanceMeters! || record.elapsedSeconds! < ordered[index - 1].elapsedSeconds!))) return []
  const rows: LapRow[] = []
  const interpolate = (distance: number) => {
    for (let index = 1; index < ordered.length; index++) {
      const before = ordered[index - 1]
      const after = ordered[index]
      if (distance <= after.distanceMeters!) {
        const fraction = (distance - before.distanceMeters!) / (after.distanceMeters! - before.distanceMeters!)
        return before.elapsedSeconds! + fraction * (after.elapsedSeconds! - before.elapsedSeconds!)
      }
    }
    return ordered.at(-1)!.elapsedSeconds!
  }
  const start = ordered[0].distanceMeters!
  const end = ordered.at(-1)!.distanceMeters!
  let boundary = (Math.floor(start / 1000) + 1) * 1000
  let previousDistance = start
  let previousTime = ordered[0].elapsedSeconds!
  while (boundary <= end) {
    const time = interpolate(boundary)
    const distance = boundary - previousDistance
    rows.push({ lapNumber: rows.length + 1, distanceMeters: distance, durationSeconds: time - previousTime, averageSpeedMetersPerSecond: time > previousTime ? distance / (time - previousTime) : null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null, source: 'derived' })
    previousDistance = boundary
    previousTime = time
    boundary += 1000
  }
  if (end > previousDistance && ordered.at(-1)!.elapsedSeconds! > previousTime) {
    const distance = end - previousDistance
    const duration = ordered.at(-1)!.elapsedSeconds! - previousTime
    rows.push({ lapNumber: rows.length + 1, distanceMeters: distance, durationSeconds: duration, averageSpeedMetersPerSecond: distance / duration, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null, source: 'derived' })
  }
  return rows
}
