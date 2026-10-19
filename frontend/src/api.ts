export type HealthResponse = {
  status: string
}

export type FixtureSummary = {
  sport: string
  startTime: string
  distanceMeters: number | null
  status: string
  recordCount: number
  lapCount: number
}

export type Activity = {
  id: number
  sport: string
  subSport: string | null
  startTime: string
  elapsedSeconds: number | null
  distanceMeters: number | null
  averageHeartRate: number | null
  ascentMeters: number | null
}

export type ActivityRecord = {
  elapsedSeconds: number | null
  distanceMeters: number | null
  latitude: number | null
  longitude: number | null
  altitudeMeters: number | null
  speedMetersPerSecond: number | null
  heartRate: number | null
  cadence: number | null
}

export type Lap = {
  lapNumber: number
  durationSeconds: number | null
  distanceMeters: number | null
  averageSpeedMetersPerSecond: number | null
  averageHeartRate: number | null
  averageCadence: number | null
  ascentMeters: number | null
  descentMeters: number | null
}

export type ActivityDetail = {
  activity: Activity
  movingSeconds: number | null
  averageSpeedMetersPerSecond: number | null
  maximumHeartRate: number | null
  averageCadence: number | null
  calories: number | null
  records: ActivityRecord[]
  laps: Lap[]
}

export type ImportOutcome = {
  status: 'IMPORTED' | 'DUPLICATE' | 'SKIPPED_NON_RUN' | 'INVALID' | 'FAILED'
  activityId: number | null
  originalRetained: boolean
  code: string | null
}

export type ImportBatchError = {
  code: 'BATCH_COUNT_EXCEEDED' | 'REQUEST_TOO_LARGE'
  message: string
}

export type ImportResponse = {
  outcomes: ImportOutcome[]
  batchError: ImportBatchError | null
}

export type IntervalsIcuActivityResult = {
  externalId: string | null
  status: 'IMPORTED' | 'ALREADY_PRESENT' | 'SKIPPED' | 'FAILED'
  code: string | null
  message: string
}

export type IntervalsIcuSyncResult = {
  started: boolean
  configured: boolean
  checked: number
  imported: number
  alreadyPresent: number
  skipped: number
  failed: number
  activities: IntervalsIcuActivityResult[]
  error: string | null
  attemptedAt: string | null
  completedAt: string | null
}

export type IntervalsIcuStatus = {
  configured: boolean
  running: boolean
  lastSuccessfulSyncAt: string | null
  lastAttemptAt: string | null
  retryAfterUntil: string | null
  lastResult: IntervalsIcuSyncResult | null
  message: string | null
}

export async function getHealth(): Promise<HealthResponse> {
  const response = await fetch('/api/health')
  if (!response.ok) throw new Error(`Health request failed: ${response.status}`)
  return response.json() as Promise<HealthResponse>
}

export async function getFixtureSummary(): Promise<FixtureSummary> {
  const response = await fetch('/api/fixture-summary')
  if (!response.ok) throw new Error(`Fixture summary request failed: ${response.status}`)
  return response.json() as Promise<FixtureSummary>
}

export async function getActivities(from: string): Promise<Activity[]> {
  const response = await fetch(`/api/activities?from=${encodeURIComponent(from)}`)
  if (!response.ok) throw new Error(`Activities request failed: ${response.status}`)
  return response.json() as Promise<Activity[]>
}

export async function getActivity(id: string): Promise<ActivityDetail> {
  const response = await fetch(`/api/activities/${encodeURIComponent(id)}`)
  if (response.status === 404) throw new Error('Lauf nicht gefunden.')
  if (!response.ok) throw new Error(`Laufdetails konnten nicht geladen werden: ${response.status}`)
  return response.json() as Promise<ActivityDetail>
}

export async function importFits(files: File[]): Promise<ImportResponse> {
  const form = new FormData()
  for (const file of files) form.append('files', file, file.name)
  const response = await fetch('/api/imports', { method: 'POST', body: form })
  if (!response.ok) throw new Error(`Import request failed: ${response.status}`)
  return response.json() as Promise<ImportResponse>
}

export async function getIntervalsIcuStatus(): Promise<IntervalsIcuStatus> {
  const response = await fetch('/api/imports/intervals-icu/status')
  if (!response.ok) throw new Error(`Intervals.icu status request failed: ${response.status}`)
  return response.json() as Promise<IntervalsIcuStatus>
}

export async function startIntervalsIcuSync(): Promise<IntervalsIcuStatus> {
  const response = await fetch('/api/imports/intervals-icu/sync', { method: 'POST' })
  if (!response.ok && response.status !== 409) throw new Error(`Intervals.icu sync request failed: ${response.status}`)
  return response.json() as Promise<IntervalsIcuStatus>
}
