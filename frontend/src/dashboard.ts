import type { Activity } from './api'
export type { Activity } from './api'

const WEEK_MS = 7 * 24 * 60 * 60 * 1000
export const sixWeekStart = (now: Date) => new Date(now.getTime() - 6 * WEEK_MS)

export function summarizeActivities(source: Activity[], now: Date) {
  const start = sixWeekStart(now)
  const activities = source
    .filter((activity) => {
      const timestamp = Date.parse(activity.startTime)
      return timestamp >= start.getTime() && timestamp <= now.getTime()
    })
    .sort((left, right) => Date.parse(right.startTime) - Date.parse(left.startTime))

  const distanceValues = activities.flatMap(({ distanceMeters }) => distanceMeters === null ? [] : [distanceMeters])
  const durationValues = activities.flatMap(({ elapsedSeconds }) => elapsedSeconds === null ? [] : [elapsedSeconds])
  const distanceMeters = activities.length && activities.every(({ distanceMeters }) => distanceMeters !== null)
    ? distanceValues.reduce((sum, value) => sum + value, 0) : null
  const durationSeconds = activities.length && activities.every(({ elapsedSeconds }) => elapsedSeconds !== null)
    ? durationValues.reduce((sum, value) => sum + value, 0) : null
  const ascentValues = activities.flatMap(({ ascentMeters }) => ascentMeters === null ? [] : [ascentMeters])
  const weeks = Array.from({ length: 6 }, (_, index) => {
    const weekStart = new Date(start.getTime() + index * WEEK_MS)
    const runs = activities.filter(({ startTime }) => {
      const time = Date.parse(startTime)
      return time >= weekStart.getTime() && (index === 5 ? time <= now.getTime() : time < weekStart.getTime() + WEEK_MS)
    })
    return {
      start: weekStart.toISOString(),
      runCount: runs.length,
      distanceMeters: runs.length === 0 ? 0 : runs.every(({ distanceMeters }) => distanceMeters !== null)
        ? runs.reduce((sum, run) => sum + run.distanceMeters!, 0) : null,
      durationSeconds: runs.length === 0 ? 0 : runs.every(({ elapsedSeconds }) => elapsedSeconds !== null)
        ? runs.reduce((sum, run) => sum + run.elapsedSeconds!, 0) : null,
    }
  })

  return {
    activities,
    weeks,
    metrics: {
      runCount: activities.length,
      distanceMeters,
      durationSeconds,
      averageDistanceMeters: activities.length && activities.every(({ distanceMeters }) => distanceMeters !== null) ? distanceMeters! / activities.length : null,
      paceSecondsPerKm: distanceMeters !== null && distanceMeters > 0 && durationSeconds !== null && activities.every(({ elapsedSeconds, distanceMeters }) => elapsedSeconds !== null && distanceMeters !== null)
        ? durationSeconds / (distanceMeters / 1000)
        : null,
      ascentMeters: ascentValues.length ? ascentValues.reduce((sum, value) => sum + value, 0) : null,
    },
  }
}
