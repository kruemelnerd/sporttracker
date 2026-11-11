export type HealthResponse = {
  status: string
}

export type VersionStatus = 'CURRENT' | 'UPDATE_AVAILABLE' | 'REBUILD_AVAILABLE' | 'DIFFERENT_RELEASE' | 'UNKNOWN'

export type VersionRegistryError = 'AUTHENTICATION_REQUIRED' | 'ACCESS_DENIED' | 'NOT_FOUND' | 'RATE_LIMITED'
  | 'REGISTRY_UNAVAILABLE' | 'TIMEOUT' | 'NETWORK_ERROR' | 'INVALID_RESPONSE'

export type VersionResponse = {
  applicationVersion: string
  installedCommit: string | null
  installedDigest: string | null
  latestCommit: string | null
  latestDigest: string | null
  updateStatus: VersionStatus
  lastCheckedAt: string | null
  errorStatus: VersionRegistryError | null
}

export async function getVersion(refresh = false): Promise<VersionResponse> {
  const response = await fetch(refresh ? '/api/version?refresh=true' : '/api/version')
  if (!response.ok) throw new Error('Versionsinformationen konnten nicht geladen werden.')
  return response.json() as Promise<VersionResponse>
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
  garminTrainingMetrics: GarminTrainingMetrics
  records: ActivityRecord[]
  laps: Lap[]
}

export type GarminTrainingMetrics = {
  aerobicTrainingEffect: number | null
  anaerobicTrainingEffect: number | null
  trainingLoad: number | null
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
  automaticSyncSuppressed?: boolean
}

export type GarminSyncResult = {
  started: boolean
  checked: number
  imported: number
  alreadyPresent: number
  skipped: number
  failed: number
  error: string | null
  attemptedAt: string | null
  completedAt: string | null
}

export type GarminStatus = {
  enabled: boolean
  apiKeyPresent: boolean
  configured: boolean
  connected: boolean
  running: boolean
  automaticSyncSuppressed: boolean
  lastAttemptAt: string | null
  lastSuccessfulSyncAt: string | null
  retryAfterUntil: string | null
  lastResult: GarminSyncResult | null
}

export type DataManagementAction = 'DELETE_GARMIN' | 'DELETE_INTERVALS_ICU' | 'DELETE_ACTIVITIES' | 'RESET_APPLICATION_DATA'
export type DataManagementStatus = {
  enabled: boolean
  garminPaused: boolean
  intervalsPaused: boolean
  pendingFileDeletions: number
}
export type DataDeletionPreview = {
  token: string
  action: DataManagementAction
  affectedActivities: number
  deletableActivities: number
  protectedActivities: number
  ambiguousActivities: number
  sourceAssociations: number
  records: number
  laps: number
  importFiles: number
  trainingPlans: number
  trainingPhases: number
  plannedSessions: number
  expiresAt: string
  preservedData: string
}
export type DataDeletionResult = {
  action: DataManagementAction
  activitiesDeleted: number
  activitiesProtected: number
  activitiesAmbiguous: number
  sourceAssociationsDeleted: number
  recordsDeleted: number
  lapsDeleted: number
  importFilesDeleted: number
  trainingPlansDeleted: number
  trainingPhasesDeleted: number
  plannedSessionsDeleted: number
  filesDeleted: number
  filesPending: number
  filesFailed: number
  garminPaused: boolean
  intervalsPaused: boolean
}

export async function getDataManagementStatus(): Promise<DataManagementStatus> {
  const response = await fetch('/api/data-management/status')
  if (!response.ok) throw new Error('Datenverwaltungsstatus konnte nicht geladen werden.')
  return response.json() as Promise<DataManagementStatus>
}

export async function previewDataDeletion(action: DataManagementAction): Promise<DataDeletionPreview> {
  const response = await fetch('/api/data-management/preview', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action }),
  })
  if (!response.ok) throw new Error(`Vorschau fehlgeschlagen (${response.status}).`)
  return response.json() as Promise<DataDeletionPreview>
}

export async function confirmDataDeletion(action: DataManagementAction, token: string, phrase: string): Promise<DataDeletionResult> {
  const response = await fetch('/api/data-management/confirm', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, token, phrase }),
  })
  if (!response.ok) throw new Error(`Löschen fehlgeschlagen (${response.status}). Bitte Vorschau neu laden.`)
  return response.json() as Promise<DataDeletionResult>
}

export async function resumeDataSync(provider: 'GARMIN' | 'INTERVALS_ICU'): Promise<void> {
  const response = await fetch(`/api/data-management/sync/${provider}/resume`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  })
  if (!response.ok) throw new Error(`Synchronisierung konnte nicht freigegeben werden (${response.status}).`)
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

export async function getGarminStatus(): Promise<GarminStatus> {
  const response = await fetch('/api/imports/garmin/status')
  if (!response.ok) throw new Error('Garmin Connect-Status konnte nicht geladen werden.')
  return response.json() as Promise<GarminStatus>
}

export async function startGarminSync(): Promise<GarminStatus> {
  const response = await fetch('/api/imports/garmin/sync', { method: 'POST' })
  if (!response.ok && response.status !== 409) throw new Error('Garmin-Synchronisierung konnte nicht gestartet werden.')
  return response.json() as Promise<GarminStatus>
}
