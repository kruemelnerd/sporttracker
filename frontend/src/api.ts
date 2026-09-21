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

export type ImportOutcome = {
  status: 'IMPORTED' | 'DUPLICATE' | 'SKIPPED' | 'INVALID' | 'FAILED'
  activityId: number | null
  originalRetained: boolean
  code: string | null
}

export type ImportResponse = { outcomes: ImportOutcome[] }

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

export async function importFit(file: File): Promise<ImportOutcome> {
  const form = new FormData()
  form.append('file', file, file.name)
  const response = await fetch('/api/imports', { method: 'POST', body: form })
  if (!response.ok) throw new Error(`Import request failed: ${response.status}`)
  const body = await response.json() as ImportResponse
  if (body.outcomes.length !== 1) throw new Error('Import response did not contain one outcome')
  return body.outcomes[0]
}
