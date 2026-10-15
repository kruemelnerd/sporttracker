import type { SessionType, Sport } from './trainingPlansApi'

export type TrainingPlanDocument = {
  schemaVersion: 1
  plan: {
    name: string
    goal: string
    startDate: string
    endDate: string
    raceDate: string | null
    targetTime: number | null
    description: string | null
    phases: { name: string; startDate: string; endDate: string; description: string | null }[]
    sessions: {
      date: string; sport: Sport; sessionType: SessionType; title: string; targetDistanceKm: number | null
      targetDurationMinutes: number | null; targetPaceFrom: number | null; targetPaceTo: number | null; targetRpe: number | null
      instructions: string | null; notes: string | null
    }[]
  }
}

const sports = ['RUN', 'BIKE', 'STRENGTH', 'OTHER']
const types = ['EASY', 'RECOVERY', 'LONG_RUN', 'TEMPO', 'INTERVAL', 'TEST', 'RACE', 'OTHER']

export function parseTrainingPlanDocument(text: string): TrainingPlanDocument {
  let parsed: unknown
  try { parsed = JSON.parse(text) } catch { throw new Error('Die Datei enthält kein gültiges JSON.') }
  const root = object(parsed, 'JSON')
  onlyKeys(root, ['schemaVersion', 'plan'], 'JSON')
  if (root.schemaVersion !== 1) throw new Error('Schema-Version wird nicht unterstützt (erwartet wird Version 1).')
  const source = object(root.plan, 'Plan')
  onlyKeys(source, ['name', 'goal', 'startDate', 'endDate', 'raceDate', 'targetTime', 'description', 'phases', 'sessions'], 'Plan')
  const startDate = date(source.startDate, 'Planbeginn')
  const endDate = date(source.endDate, 'Planende')
  if (endDate < startDate) throw new Error('Planende darf nicht vor dem Planbeginn liegen.')
  const name = string(source.name, 'Planname')
  const goal = string(source.goal, 'Wettkampfziel')
  const raceDate = optionalDate(source.raceDate, 'Wettkampfdatum')
  if (raceDate && (raceDate < startDate || raceDate > endDate)) throw new Error('Wettkampfdatum muss innerhalb des Plans liegen.')
  const targetTime = optionalNumber(source.targetTime, 'Zielzeit', true)
  const description = optionalString(source.description, 'Beschreibung')
  if (!Array.isArray(source.phases) || !Array.isArray(source.sessions)) throw new Error('Phasen und Trainingseinheiten müssen als Listen enthalten sein.')

  const phases = source.phases.map((value, index) => {
    const phase = object(value, `Phase ${index + 1}`)
    onlyKeys(phase, ['name', 'startDate', 'endDate', 'description'], `Phase ${index + 1}`)
    const phaseStart = date(phase.startDate, 'Phasenbeginn')
    const phaseEnd = date(phase.endDate, 'Phasenende')
    if (phaseEnd < phaseStart || phaseStart < startDate || phaseEnd > endDate) throw new Error(`Phase ${index + 1}: Zeitraum muss innerhalb des Plans liegen.`)
    return { name: string(phase.name, 'Phasenname'), startDate: phaseStart, endDate: phaseEnd, description: optionalString(phase.description, 'Phasenbeschreibung') }
  })

  const sessions = source.sessions.map((value, index) => {
    const session = object(value, `Einheit ${index + 1}`)
    onlyKeys(session, ['date', 'sport', 'sessionType', 'title', 'targetDistanceKm', 'targetDurationMinutes', 'targetPaceFrom', 'targetPaceTo', 'targetRpe', 'instructions', 'notes'], `Einheit ${index + 1}`)
    const sessionDate = date(session.date, 'Einheitendatum')
    if (sessionDate < startDate || sessionDate > endDate) throw new Error(`Einheit ${index + 1}: Datum muss innerhalb des Plans liegen.`)
    if (!sports.includes(String(session.sport))) throw new Error(`Einheit ${index + 1}: Sportart ist ungültig.`)
    if (!types.includes(String(session.sessionType))) throw new Error(`Einheit ${index + 1}: Trainingstyp ist ungültig.`)
    const targetDistanceKm = optionalNumber(session.targetDistanceKm, 'Zieldistanz')
    const targetDurationMinutes = optionalNumber(session.targetDurationMinutes, 'Zieldauer', true)
    const targetPaceFrom = optionalNumber(session.targetPaceFrom, 'Pace von')
    const targetPaceTo = optionalNumber(session.targetPaceTo, 'Pace bis')
    const targetRpe = optionalNumber(session.targetRpe, 'RPE', true)
    if (targetRpe !== null && (targetRpe < 1 || targetRpe > 10)) throw new Error(`Einheit ${index + 1}: RPE muss zwischen 1 und 10 liegen.`)
    if (targetPaceFrom !== null && targetPaceTo !== null && targetPaceFrom > targetPaceTo) throw new Error(`Einheit ${index + 1}: Pace von muss kleiner oder gleich Pace bis sein.`)
    return {
      date: sessionDate, sport: session.sport as Sport, sessionType: session.sessionType as SessionType, title: string(session.title, 'Einheitentitel'),
      targetDistanceKm, targetDurationMinutes, targetPaceFrom, targetPaceTo, targetRpe,
      instructions: optionalString(session.instructions, 'Anweisung'), notes: optionalString(session.notes, 'Notizen'),
    }
  })
  return { schemaVersion: 1, plan: { name, goal, startDate, endDate, raceDate, targetTime, description, phases, sessions } }
}

function object(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} muss ein JSON-Objekt sein.`)
  return value as Record<string, unknown>
}

function onlyKeys(value: Record<string, unknown>, allowed: string[], label: string) {
  const extra = Object.keys(value).find((key) => !allowed.includes(key))
  if (extra) throw new Error(`${label}: Unbekanntes Feld „${extra}“.`)
}

function string(value: unknown, label: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`${label} darf nicht leer sein.`)
  return value.trim()
}

function optionalString(value: unknown, label: string): string | null {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string') throw new Error(`${label} muss Text sein.`)
  return value
}

function date(value: unknown, label: string): string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw new Error(`${label}: ungültiges Datum.`)
  const [year, month, day] = value.split('-').map(Number)
  const parsed = new Date(year, month - 1, day, 12)
  if (parsed.getFullYear() !== year || parsed.getMonth() !== month - 1 || parsed.getDate() !== day) throw new Error(`${label}: ungültiges Datum.`)
  return value
}

function optionalDate(value: unknown, label: string): string | null {
  return value === undefined || value === null ? null : date(value, label)
}

function optionalNumber(value: unknown, label: string, integer = false): number | null {
  if (value === undefined || value === null) return null
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0 || (integer && !Number.isInteger(value))) throw new Error(`${label} muss ${integer ? 'eine positive ganze Zahl' : 'größer als 0'} sein.`)
  return value
}
