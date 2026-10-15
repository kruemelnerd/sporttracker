export type Sport = 'RUN' | 'BIKE' | 'STRENGTH' | 'OTHER'
export type SessionType = 'EASY' | 'RECOVERY' | 'LONG_RUN' | 'TEMPO' | 'INTERVAL' | 'TEST' | 'RACE' | 'OTHER'

export type TrainingPlanSummary = {
  id: string
  name: string
  goal: string
  startDate: string
  endDate: string
  raceDate: string | null
  targetTime: number | null
  description: string | null
}

export type TrainingPhase = {
  id: string
  trainingPlanId: string
  name: string
  startDate: string
  endDate: string
  description: string | null
}

export type PlannedSession = {
  id: string
  trainingPlanId: string
  date: string
  sport: Sport
  sessionType: SessionType
  title: string
  targetDistanceKm: number | null
  targetDurationMinutes: number | null
  targetPaceFrom: number | null
  targetPaceTo: number | null
  targetRpe: number | null
  instructions: string | null
  notes: string | null
}

export type TrainingPlan = TrainingPlanSummary & { phases: TrainingPhase[]; sessions: PlannedSession[] }
export type PlanInput = Omit<TrainingPlanSummary, 'id'>
export type PhaseInput = Omit<TrainingPhase, 'id' | 'trainingPlanId'> & { id?: string }
export type SessionInput = Omit<PlannedSession, 'id' | 'trainingPlanId'>

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    const problem = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(problem?.message ?? `Anfrage fehlgeschlagen (${response.status}).`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

const json = (body: unknown): RequestInit => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
const putJson = (body: unknown): RequestInit => ({ ...json(body), method: 'PUT' })

export function listTrainingPlans(): Promise<TrainingPlanSummary[]> {
  return request('/api/training-plans')
}

export function getTrainingPlan(id: string): Promise<TrainingPlan> {
  return request(`/api/training-plans/${encodeURIComponent(id)}`)
}

export function createTrainingPlan(input: PlanInput): Promise<TrainingPlan> {
  return request('/api/training-plans', json(input))
}

export function updateTrainingPlan(id: string, input: PlanInput): Promise<TrainingPlan> {
  return request(`/api/training-plans/${encodeURIComponent(id)}`, putJson(input))
}

export function deleteTrainingPlan(id: string): Promise<void> {
  return request(`/api/training-plans/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function replaceTrainingPhases(id: string, phases: PhaseInput[]): Promise<TrainingPhase[]> {
  return request(`/api/training-plans/${encodeURIComponent(id)}/phases`, putJson(phases))
}

export function getPlannedSessions(id: string, from: string, to: string): Promise<PlannedSession[]> {
  const query = new URLSearchParams({ from, to })
  return request(`/api/training-plans/${encodeURIComponent(id)}/sessions?${query}`)
}

export function createPlannedSession(id: string, input: SessionInput): Promise<PlannedSession> {
  return request(`/api/training-plans/${encodeURIComponent(id)}/sessions`, json(input))
}

export function updatePlannedSession(id: string, sessionId: string, input: SessionInput): Promise<PlannedSession> {
  return request(`/api/training-plans/${encodeURIComponent(id)}/sessions/${encodeURIComponent(sessionId)}`, putJson(input))
}

export function deletePlannedSession(id: string, sessionId: string): Promise<void> {
  return request(`/api/training-plans/${encodeURIComponent(id)}/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
}
