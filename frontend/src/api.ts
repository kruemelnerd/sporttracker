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

export async function importFits(files: File[]): Promise<ImportResponse> {
  const form = new FormData()
  for (const file of files) form.append('files', file, file.name)
  const response = await fetch('/api/imports', { method: 'POST', body: form })
  if (!response.ok) throw new Error(`Import request failed: ${response.status}`)
  return response.json() as Promise<ImportResponse>
}
