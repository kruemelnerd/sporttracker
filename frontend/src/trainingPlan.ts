export type PlannedSessionLike = {
  sport: string
  targetDistanceKm: number | null
  targetDurationMinutes: number | null
}

export type TrainingPhaseLike = {
  name: string
  startDate: string
  endDate: string
}

function localDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day, 12)
}

function dateString(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function mondayOfLocalDate(value: string): string {
  const date = localDate(value)
  date.setDate(date.getDate() - (date.getDay() + 6) % 7)
  return dateString(date)
}

export function shiftWeek(monday: string, amount: number): string {
  const date = localDate(monday)
  date.setDate(date.getDate() + amount * 7)
  return dateString(date)
}

export function isoWeekLabel(monday: string): string {
  const thursday = localDate(monday)
  thursday.setDate(thursday.getDate() + 3)
  const year = thursday.getFullYear()
  const firstMonday = localDate(`${year}-01-04`)
  firstMonday.setDate(firstMonday.getDate() - (firstMonday.getDay() + 6) % 7)
  const week = 1 + Math.round((localDate(monday).getTime() - firstMonday.getTime()) / (7 * 24 * 60 * 60 * 1000))
  return `KW ${week} · ${year}`
}

export function initialPlanWeek(startDate: string, endDate: string, today: string): string {
  const current = mondayOfLocalDate(today)
  const first = mondayOfLocalDate(startDate)
  const last = mondayOfLocalDate(endDate)
  if (current < first) return first
  if (current > last) return last
  return current
}

export function summarizeWeek(sessions: PlannedSessionLike[]) {
  return sessions.reduce((summary, session) => {
    if (session.sport === 'RUN') {
      summary.runCount++
      summary.runDistanceKm += session.targetDistanceKm ?? 0
    } else {
      summary.otherSessionCount++
    }
    summary.durationMinutes += session.targetDurationMinutes ?? 0
    return summary
  }, { runCount: 0, runDistanceKm: 0, durationMinutes: 0, otherSessionCount: 0 })
}

export function phaseForWeek(phases: TrainingPhaseLike[], monday: string): TrainingPhaseLike | null {
  const sunday = shiftWeek(monday, 1)
  const lastDayDate = localDate(sunday)
  lastDayDate.setDate(lastDayDate.getDate() - 1)
  const lastDay = dateString(lastDayDate)
  return phases.find((phase) => phase.startDate <= lastDay && phase.endDate >= monday) ?? null
}
