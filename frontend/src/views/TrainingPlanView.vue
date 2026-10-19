<template>
  <section class="training-plan" aria-labelledby="training-plan-title">
    <header class="page-heading">
      <div><p class="eyebrow">DEIN TRAININGSPLAN</p><h1 id="training-plan-title">Trainingsplan</h1></div>
      <button type="button" class="primary" aria-label="Trainingsplan erstellen" @click="openPlanForm()">Trainingsplan erstellen</button>
    </header>

    <p v-if="loading" role="status">Trainingspläne werden geladen …</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!loading && !plans.length" class="empty-state">Noch kein Trainingsplan vorhanden. Erstelle einen Plan, um deine Wochen zu organisieren.</p>

    <form v-if="showPlanForm" class="panel editor" :aria-label="editingPlan ? 'Trainingsplan bearbeiten' : 'Trainingsplan erstellen'" @submit.prevent="savePlan">
      <h2>{{ editingPlan ? 'Trainingsplan bearbeiten' : 'Trainingsplan erstellen' }}</h2>
      <label for="plan-name">Name</label><input id="plan-name" v-model="planForm.name" name="planName" required maxlength="200">
      <label for="plan-goal">Wettkampfziel</label><input id="plan-goal" v-model="planForm.goal" name="goal" required maxlength="200">
      <div class="form-grid">
        <div><label for="plan-start">Planbeginn</label><input id="plan-start" v-model="planForm.startDate" name="startDate" type="date" required></div>
        <div><label for="plan-end">Planende</label><input id="plan-end" v-model="planForm.endDate" name="endDate" type="date" required></div>
        <div><label for="plan-race">Wettkampfdatum (optional)</label><input id="plan-race" v-model="planForm.raceDate" type="date"></div>
        <div><label for="plan-target-time">Zielzeit in Minuten (optional)</label><input id="plan-target-time" v-model="planForm.targetTime" type="number" min="1" step="1"></div>
      </div>
      <label for="plan-description">Beschreibung (optional)</label><textarea id="plan-description" v-model="planForm.description" rows="2" />
      <div class="form-actions"><button class="primary" type="submit">{{ editingPlan ? 'Änderungen speichern' : 'Trainingsplan speichern' }}</button><button type="button" @click="showPlanForm = false">Abbrechen</button></div>
    </form>

    <section class="panel json-panel" aria-labelledby="json-import-title">
      <h2 id="json-import-title">Trainingsplan als JSON importieren</h2>
      <label for="training-plan-file">JSON-Datei auswählen</label>
      <input id="training-plan-file" type="file" accept=".json,application/json" @change="previewImport">
      <div v-if="importDocument" class="import-preview" aria-label="Importvorschau">
        <h3>{{ importDocument.plan.name }}</h3>
        <p>{{ importDocument.plan.goal }} · {{ formatDate(importDocument.plan.startDate) }} – {{ formatDate(importDocument.plan.endDate) }}</p>
        <p>{{ importDocument.plan.phases.length }} Phasen · {{ importDocument.plan.sessions.length }} Trainingseinheiten</p>
        <div class="form-actions">
          <button type="button" class="primary" aria-label="Import bestätigen" :disabled="importing" @click="confirmImport(false)">Plan importieren</button>
          <button v-if="importDuplicate" type="button" class="danger" aria-label="Bestehenden Plan bewusst ersetzen" :disabled="importing" @click="confirmImport(true)">Bestehenden Plan bewusst ersetzen</button>
          <button type="button" :disabled="importing" @click="clearImport">Abbrechen</button>
        </div>
      </div>
    </section>

    <template v-if="!loading && plan">
      <div class="plan-picker panel">
        <label for="plan-select">Gewählter Trainingsplan</label>
        <select id="plan-select" v-model="selectedId" @change="selectPlan(selectedId)">
          <option v-for="item in plans" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <div class="plan-intro">
          <div><h2>{{ plan.name }}</h2><p>{{ plan.goal }}<span v-if="plan.raceDate"> · Wettkampf am {{ formatDate(plan.raceDate) }}</span></p></div>
          <div class="plan-actions">
            <p><strong>Aktuelle Phase:</strong> {{ phase?.name ?? 'Keine Phase' }}</p>
            <button type="button" @click="downloadPlan">JSON exportieren</button>
            <button type="button" @click="openPlanForm(true)">Plan bearbeiten</button>
            <button type="button" class="danger" @click="removePlan">Plan löschen</button>
          </div>
        </div>
      </div>

      <section class="panel phase-panel" aria-labelledby="phase-heading">
        <div class="section-heading"><h2 id="phase-heading">Trainingsphasen</h2><button type="button" @click="openPhaseForm()">Phase hinzufügen</button></div>
        <p v-if="!plan.phases.length" class="day-empty">Noch keine Trainingsphasen.</p>
        <ul v-else class="phase-list"><li v-for="item in plan.phases" :key="item.id">
          <span><strong>{{ item.name }}</strong> · {{ formatDate(item.startDate) }} – {{ formatDate(item.endDate) }}<small v-if="item.description">{{ item.description }}</small></span>
          <span class="form-actions"><button type="button" :aria-label="`Phase bearbeiten: ${item.name}`" @click="openPhaseForm(item)">Bearbeiten</button><button type="button" class="danger" :aria-label="`Phase löschen: ${item.name}`" @click="removePhase(item.id)">Löschen</button></span>
        </li></ul>
        <form v-if="showPhaseForm" class="editor sub-editor" aria-label="Trainingsphase bearbeiten" @submit.prevent="savePhase">
          <h3>{{ phaseForm.id ? 'Trainingsphase bearbeiten' : 'Trainingsphase hinzufügen' }}</h3>
          <label for="phase-name">Name</label><input id="phase-name" v-model="phaseForm.name" required maxlength="200">
          <div class="form-grid"><div><label for="phase-start">Beginn</label><input id="phase-start" v-model="phaseForm.startDate" type="date" required></div><div><label for="phase-end">Ende</label><input id="phase-end" v-model="phaseForm.endDate" type="date" required></div></div>
          <label for="phase-description">Beschreibung (optional)</label><textarea id="phase-description" v-model="phaseForm.description" rows="2" />
          <div class="form-actions"><button class="primary" type="submit">Phase speichern</button><button type="button" @click="showPhaseForm = false">Abbrechen</button></div>
        </form>
      </section>

      <section class="week-section" aria-labelledby="week-heading">
        <div class="week-navigation">
          <button type="button" :disabled="weekStart <= firstWeek" aria-label="Vorige Woche" @click="changeWeek(-1)">← Vorige</button>
          <h2 id="week-heading">{{ isoWeekLabel(weekStart) }} <span>{{ formatDate(weekStart) }} – {{ formatDate(weekEnd) }}</span></h2>
          <button type="button" :disabled="weekStart >= lastWeek" aria-label="Nächste Woche" @click="changeWeek(1)">Nächste →</button>
        </div>

        <dl class="week-metrics" aria-label="Kennzahlen der Trainingswoche">
          <div><dt>Laufeinheiten</dt><dd data-testid="run-count">{{ metrics.runCount }}</dd></div>
          <div><dt>Laufkilometer</dt><dd data-testid="run-distance">{{ distance(metrics.runDistanceKm) }}</dd></div>
          <div><dt>Trainingsdauer</dt><dd data-testid="duration">{{ duration(metrics.durationMinutes) }}</dd></div>
          <div><dt>Weitere Sporteinheiten</dt><dd data-testid="other-sessions">{{ metrics.otherSessionCount }}</dd></div>
          <div><dt>Trainingsphase</dt><dd data-testid="week-phase">{{ phase?.name ?? '–' }}</dd></div>
        </dl>

        <div class="section-heading"><h3>Geplante Einheiten</h3><button type="button" aria-label="Training hinzufügen" @click="openSessionForm()">Training hinzufügen</button></div>
        <form v-if="showSessionForm" class="panel editor sub-editor" :aria-label="editingSessionId ? 'Training bearbeiten' : 'Training hinzufügen'" @submit.prevent="saveSession">
          <h3>{{ editingSessionId ? 'Training bearbeiten' : 'Training hinzufügen' }}</h3>
          <div class="form-grid">
            <div><label for="session-date">Datum</label><input id="session-date" v-model="sessionForm.date" name="sessionDate" type="date" :min="plan.startDate" :max="plan.endDate" required></div>
            <div><label for="session-sport">Sportart</label><select id="session-sport" v-model="sessionForm.sport"><option value="RUN">Laufen</option><option value="BIKE">Rad</option><option value="STRENGTH">Kraft/Stabi</option><option value="OTHER">Sonstiges</option></select></div>
            <div><label for="session-type">Trainingstyp</label><select id="session-type" v-model="sessionForm.sessionType"><option v-for="option in sessionTypes" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
            <div><label for="session-title">Titel</label><input id="session-title" v-model="sessionForm.title" name="sessionTitle" required maxlength="200"></div>
            <div><label for="session-distance">Zieldistanz (km, optional)</label><input id="session-distance" v-model="sessionForm.targetDistanceKm" type="number" min="0.01" step="0.01"></div>
            <div><label for="session-duration">Zieldauer (Minuten, optional)</label><input id="session-duration" v-model="sessionForm.targetDurationMinutes" type="number" min="1" step="1"></div>
            <div><label for="session-pace-from">Pace von (min/km, optional)</label><input id="session-pace-from" v-model="sessionForm.targetPaceFrom" type="number" min="0.1" step="0.1"></div>
            <div><label for="session-pace-to">Pace bis (min/km, optional)</label><input id="session-pace-to" v-model="sessionForm.targetPaceTo" type="number" min="0.1" step="0.1"></div>
            <div><label for="session-rpe">Intensität RPE (1–10, optional)</label><input id="session-rpe" v-model="sessionForm.targetRpe" name="targetRpe" type="number" min="1" max="10" step="1"></div>
          </div>
          <label for="session-instructions">Anweisung (optional)</label><textarea id="session-instructions" v-model="sessionForm.instructions" rows="2" />
          <label for="session-notes">Notizen (optional)</label><textarea id="session-notes" v-model="sessionForm.notes" rows="2" />
          <div class="form-actions"><button class="primary" type="submit">Einheit speichern</button><button type="button" @click="showSessionForm = false">Abbrechen</button></div>
        </form>

        <p v-if="sessionsLoading" role="status">Woche wird geladen …</p>
        <p v-else-if="!sessions.length" class="week-empty">Keine geplanten Einheiten in dieser Woche.</p>
        <ol class="days" aria-label="Geplante Trainingseinheiten dieser Woche">
          <li v-for="day in days" :key="day.date" :data-testid="`week-day-${day.date}`" class="day-card" :class="{ 'is-today': day.date === today }">
            <h3>{{ day.label }}</h3>
            <ul v-if="day.sessions.length" class="sessions">
              <li v-for="session in day.sessions" :key="session.id" class="session-card">
                <p class="session-type">{{ sportLabel(session.sport) }} · {{ typeLabel(session.sessionType) }}</p>
                <h4>{{ session.title }}</h4>
                <p class="session-target">
                  <span v-if="session.targetDistanceKm !== null">{{ distance(session.targetDistanceKm) }}</span>
                  <span v-if="session.targetDurationMinutes !== null">{{ duration(session.targetDurationMinutes) }}</span>
                  <span v-if="session.targetRpe !== null">RPE {{ session.targetRpe }}</span>
                  <span v-if="session.targetPaceFrom !== null">{{ session.targetPaceFrom }}–{{ session.targetPaceTo ?? session.targetPaceFrom }} min/km</span>
                </p>
                <p v-if="session.instructions" class="instructions">{{ session.instructions }}</p>
                <div class="form-actions session-actions"><button type="button" :aria-label="`Training bearbeiten: ${session.title}`" @click="openSessionForm(session)">Bearbeiten / verschieben</button><button type="button" class="danger" :aria-label="`Training löschen: ${session.title}`" @click="removeSession(session)">Löschen</button></div>
              </li>
            </ul>
            <p v-else class="day-empty">Keine Einheit</p>
          </li>
        </ol>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  getPlannedSessions,
  getTrainingPlan,
  listTrainingPlans,
  createTrainingPlan,
  updateTrainingPlan,
  deleteTrainingPlan,
  replaceTrainingPhases,
  createPlannedSession,
  updatePlannedSession,
  deletePlannedSession,
  exportTrainingPlan,
  importTrainingPlan,
  type PlanInput,
  type PhaseInput,
  type SessionInput,
  type TrainingPhase,
  type PlannedSession,
  type TrainingPlan,
  type TrainingPlanSummary,
} from '../trainingPlansApi'
import { initialPlanWeek, isoWeekLabel, mondayOfLocalDate, phaseForWeek, shiftWeek, summarizeWeek } from '../trainingPlan'
import { parseTrainingPlanDocument, type TrainingPlanDocument } from '../trainingPlanJson'

const loading = ref(true)
const sessionsLoading = ref(false)
const error = ref('')
const plans = ref<TrainingPlanSummary[]>([])
const selectedId = ref('')
const plan = ref<TrainingPlan | null>(null)
const sessions = ref<PlannedSession[]>([])
const weekStart = ref('')
const showPlanForm = ref(false)
const editingPlan = ref(false)
const showPhaseForm = ref(false)
const showSessionForm = ref(false)
const editingSessionId = ref<string | null>(null)
const planForm = ref(blankPlanForm())
const phaseForm = ref(blankPhaseForm())
const sessionForm = ref(blankSessionForm())
const importDocument = ref<TrainingPlanDocument | null>(null)
const importDuplicate = ref(false)
const importing = ref(false)
const sessionTypes = [
  { value: 'EASY', label: 'Locker Dauerlauf' }, { value: 'RECOVERY', label: 'Regeneration' }, { value: 'LONG_RUN', label: 'Langer Lauf' },
  { value: 'TEMPO', label: 'Tempo' }, { value: 'INTERVAL', label: 'Intervalle' }, { value: 'TEST', label: 'Fitness-Test' },
  { value: 'RACE', label: 'Wettkampf' }, { value: 'OTHER', label: 'Sonstiges' },
] as const
const today = localDateString(new Date())
const firstWeek = computed(() => plan.value ? mondayOfLocalDate(plan.value.startDate) : '')
const lastWeek = computed(() => plan.value ? mondayOfLocalDate(plan.value.endDate) : '')
const weekEnd = computed(() => addDays(weekStart.value, 6))
const phase = computed(() => plan.value ? phaseForWeek(plan.value.phases ?? [], weekStart.value) : null)
const metrics = computed(() => summarizeWeek(sessions.value))
const days = computed(() => Array.from({ length: 7 }, (_, index) => {
  const date = addDays(weekStart.value, index)
  return { date, label: formatDate(date, true), sessions: sessions.value.filter((session) => session.date === date) }
}))

onMounted(async () => {
  try {
    plans.value = await listTrainingPlans()
    if (plans.value.length) {
      selectedId.value = plans.value[0].id
      await selectPlan(selectedId.value)
    }
  } catch (cause) {
    error.value = message(cause)
  } finally {
    loading.value = false
  }
})

watch([weekStart, () => plan.value?.id], async ([week]) => {
  if (!week || !plan.value) return
  await loadWeek()
})

async function loadWeek() {
  if (!weekStart.value || !plan.value) return
  sessionsLoading.value = true
  try {
    sessions.value = await getPlannedSessions(plan.value.id, weekStart.value, weekEnd.value)
  } catch (cause) {
    error.value = message(cause)
  } finally {
    sessionsLoading.value = false
  }
}

async function selectPlan(id: string) {
  if (!id) return
  error.value = ''
  try {
    plan.value = await getTrainingPlan(id)
    weekStart.value = initialPlanWeek(plan.value.startDate, plan.value.endDate, today)
  } catch (cause) {
    error.value = message(cause)
  }
}

function openPlanForm(edit = false) {
  editingPlan.value = edit
  planForm.value = edit && plan.value ? {
    name: plan.value.name, goal: plan.value.goal, startDate: plan.value.startDate, endDate: plan.value.endDate,
    raceDate: plan.value.raceDate ?? '', targetTime: plan.value.targetTime?.toString() ?? '', description: plan.value.description ?? '',
  } : blankPlanForm()
  showPlanForm.value = true
  error.value = ''
}

async function savePlan() {
  error.value = ''
  const input: PlanInput = {
    name: planForm.value.name.trim(), goal: planForm.value.goal.trim(), startDate: planForm.value.startDate, endDate: planForm.value.endDate,
    raceDate: planForm.value.raceDate || null, targetTime: optionalNumber(planForm.value.targetTime), description: planForm.value.description.trim() || null,
  }
  try {
    const saved = editingPlan.value && plan.value ? await updateTrainingPlan(plan.value.id, input) : await createTrainingPlan(input)
    plans.value = editingPlan.value ? plans.value.map((item) => item.id === saved.id ? summary(saved) : item) : [...plans.value, summary(saved)]
    selectedId.value = saved.id
    plan.value = saved
    showPlanForm.value = false
    weekStart.value = initialPlanWeek(saved.startDate, saved.endDate, today)
    await loadWeek()
  } catch (cause) { error.value = message(cause) }
}

async function removePlan() {
  if (!plan.value || !window.confirm(`Trainingsplan „${plan.value.name}“ mit allen Phasen und Einheiten löschen?`)) return
  try {
    await deleteTrainingPlan(plan.value.id)
    plans.value = plans.value.filter((item) => item.id !== plan.value?.id)
    plan.value = null
    selectedId.value = ''
    sessions.value = []
  } catch (cause) { error.value = message(cause) }
}

function openPhaseForm(phase?: TrainingPhase) {
  phaseForm.value = phase ? { id: phase.id, name: phase.name, startDate: phase.startDate, endDate: phase.endDate, description: phase.description ?? '' } : blankPhaseForm()
  showPhaseForm.value = true
  error.value = ''
}

async function savePhase() {
  if (!plan.value) return
  const current = plan.value.phases.filter((item) => item.id !== phaseForm.value.id)
  const updated = [...current, {
    ...(phaseForm.value.id ? { id: phaseForm.value.id } : {}), name: phaseForm.value.name.trim(),
    startDate: phaseForm.value.startDate, endDate: phaseForm.value.endDate, description: phaseForm.value.description.trim() || null,
  } satisfies PhaseInput]
  try {
    plan.value = { ...plan.value, phases: await replaceTrainingPhases(plan.value.id, updated) }
    showPhaseForm.value = false
    error.value = ''
  } catch (cause) { error.value = message(cause) }
}

async function removePhase(id: string) {
  if (!plan.value) return
  const phase = plan.value.phases.find((item) => item.id === id)
  if (!phase || !window.confirm(`Trainingsphase „${phase.name}“ löschen?`)) return
  try {
    plan.value = { ...plan.value, phases: await replaceTrainingPhases(plan.value.id, plan.value.phases.filter((item) => item.id !== id)) }
    error.value = ''
  } catch (cause) { error.value = message(cause) }
}

function openSessionForm(session?: PlannedSession) {
  editingSessionId.value = session?.id ?? null
  sessionForm.value = session ? {
    date: session.date, sport: session.sport, sessionType: session.sessionType, title: session.title,
    targetDistanceKm: session.targetDistanceKm?.toString() ?? '', targetDurationMinutes: session.targetDurationMinutes?.toString() ?? '',
    targetPaceFrom: session.targetPaceFrom?.toString() ?? '', targetPaceTo: session.targetPaceTo?.toString() ?? '',
    targetRpe: session.targetRpe?.toString() ?? '', instructions: session.instructions ?? '', notes: session.notes ?? '',
  } : { ...blankSessionForm(), date: weekStart.value }
  showSessionForm.value = true
  error.value = ''
}

async function saveSession() {
  if (!plan.value) return
  error.value = ''
  const input: SessionInput = {
    date: sessionForm.value.date, sport: sessionForm.value.sport as SessionInput['sport'], sessionType: sessionForm.value.sessionType as SessionInput['sessionType'],
    title: sessionForm.value.title.trim(), targetDistanceKm: optionalNumber(sessionForm.value.targetDistanceKm),
    targetDurationMinutes: optionalNumber(sessionForm.value.targetDurationMinutes), targetPaceFrom: optionalNumber(sessionForm.value.targetPaceFrom),
    targetPaceTo: optionalNumber(sessionForm.value.targetPaceTo), targetRpe: optionalNumber(sessionForm.value.targetRpe),
    instructions: sessionForm.value.instructions.trim() || null, notes: sessionForm.value.notes.trim() || null,
  }
  try {
    if (editingSessionId.value) await updatePlannedSession(plan.value.id, editingSessionId.value, input)
    else await createPlannedSession(plan.value.id, input)
    showSessionForm.value = false
    editingSessionId.value = null
    await loadWeek()
  } catch (cause) { error.value = message(cause) }
}

async function removeSession(session: PlannedSession) {
  if (!plan.value || !window.confirm(`Training „${session.title}“ löschen?`)) return
  try {
    await deletePlannedSession(plan.value.id, session.id)
    await loadWeek()
    error.value = ''
  } catch (cause) { error.value = message(cause) }
}

async function previewImport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  importDocument.value = null
  importDuplicate.value = false
  error.value = ''
  if (!file) return
  try { importDocument.value = parseTrainingPlanDocument(await file.text()) }
  catch (cause) { error.value = message(cause) }
  finally { input.value = '' }
}

async function confirmImport(replaceExisting: boolean) {
  if (!importDocument.value) return
  importing.value = true
  error.value = ''
  try {
    const result = await importTrainingPlan(importDocument.value, replaceExisting)
    const item = summary(result.plan)
    plans.value = plans.value.some((existing) => existing.id === item.id)
      ? plans.value.map((existing) => existing.id === item.id ? item : existing)
      : [...plans.value, item]
    selectedId.value = item.id
    plan.value = result.plan
    weekStart.value = initialPlanWeek(item.startDate, item.endDate, today)
    importDocument.value = null
    importDuplicate.value = false
    await loadWeek()
  } catch (cause) {
    error.value = message(cause)
    importDuplicate.value = isDuplicateError(cause)
  } finally { importing.value = false }
}

function clearImport() {
  importDocument.value = null
  importDuplicate.value = false
  error.value = ''
}

async function downloadPlan() {
  if (!plan.value) return
  try {
    const document = await exportTrainingPlan(plan.value.id)
    const url = URL.createObjectURL(new Blob([JSON.stringify(document, null, 2)], { type: 'application/json' }))
    const link = window.document.createElement('a')
    link.href = url
    link.download = `${plan.value.name.toLowerCase().replace(/[^a-z0-9-]+/g, '-')}.json`
    link.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (cause) { error.value = message(cause) }
}

function changeWeek(amount: number) {
  const next = shiftWeek(weekStart.value, amount)
  if (next >= firstWeek.value && next <= lastWeek.value) weekStart.value = next
}

function localDateString(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
function localDate(value: string) {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day, 12)
}
function addDays(value: string, amount: number) {
  const date = localDate(value)
  date.setDate(date.getDate() + amount)
  return localDateString(date)
}
function formatDate(value: string, weekday = false) {
  return new Intl.DateTimeFormat('de-DE', weekday
    ? { weekday: 'long', day: 'numeric', month: 'short' }
    : { day: 'numeric', month: 'short', year: 'numeric' }).format(localDate(value))
}
function distance(km: number) {
  return `${new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(km)} km`
}
function duration(minutes: number) {
  const hours = Math.floor(minutes / 60)
  const remainder = minutes % 60
  return hours ? `${hours} h ${remainder} min` : `${minutes} min`
}
function sportLabel(sport: PlannedSession['sport']) {
  return { RUN: 'Laufen', BIKE: 'Rad', STRENGTH: 'Kraft', OTHER: 'Sonstiges' }[sport]
}
function typeLabel(type: PlannedSession['sessionType']) {
  return { EASY: 'Locker', RECOVERY: 'Regeneration', LONG_RUN: 'Langer Lauf', TEMPO: 'Tempo', INTERVAL: 'Intervalle', TEST: 'Test', RACE: 'Wettkampf', OTHER: 'Einheit' }[type]
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : 'Trainingsdaten konnten nicht geladen werden.' }
function optionalNumber(value: string): number | null { return value === '' ? null : Number(value) }
function isDuplicateError(cause: unknown): boolean { return cause !== null && typeof cause === 'object' && 'status' in cause && cause.status === 409 }
function summary(value: TrainingPlan): TrainingPlanSummary { const { phases: _phases, sessions: _sessions, ...planSummary } = value; return planSummary }
function blankPlanForm() { return { name: '', goal: '', startDate: '', endDate: '', raceDate: '', targetTime: '', description: '' } }
function blankPhaseForm() { return { id: '', name: '', startDate: '', endDate: '', description: '' } }
function blankSessionForm() { return { date: '', sport: 'RUN', sessionType: 'EASY', title: '', targetDistanceKm: '', targetDurationMinutes: '', targetPaceFrom: '', targetPaceTo: '', targetRpe: '', instructions: '', notes: '' } }
</script>

<style scoped>
.training-plan { display: grid; gap: 1.25rem; min-width: 0; }
.page-heading, .plan-intro, .week-navigation { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.page-heading h1 { margin: 0; color: var(--color-ink); font-size: clamp(1.7rem, 5vw, 2.3rem); line-height: 1.15; }
.eyebrow, .session-type { margin: 0 0 .25rem; color: var(--color-accent-strong); font-size: .75rem; font-weight: 800; letter-spacing: .08em; }
button, select { min-height: 44px; padding: .55rem .8rem; font: inherit; border: 1px solid var(--color-border); border-radius: var(--radius-control); }
button { color: var(--color-ink); background: var(--color-surface); cursor: pointer; }
button:disabled { opacity: .5; cursor: not-allowed; }
button.primary { color: var(--color-ink); background: var(--color-accent); border-color: var(--color-accent); font-weight: 700; }
.panel, .week-section { padding: 1rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-card); }
.editor { display: grid; gap: .55rem; }
.editor h2, .editor h3, .section-heading h2, .section-heading h3 { margin: 0; color: var(--color-ink); }
.editor label { margin-top: .35rem; color: var(--color-muted); font-size: .85rem; font-weight: 700; }
.editor input, .editor select, .editor textarea { width: 100%; min-width: 0; padding: .6rem .7rem; color: var(--color-text); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-control); }
.editor textarea { resize: vertical; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: .7rem; }
.form-grid > div { min-width: 0; }
.form-grid > div label { display: block; }
.form-actions, .section-heading, .plan-actions { display: flex; flex-wrap: wrap; align-items: center; gap: .5rem; }
.form-actions { margin-top: .5rem; }
.section-heading { justify-content: space-between; }
.plan-actions { justify-content: flex-end; }
.plan-actions p { width: 100%; }
.phase-panel { display: grid; gap: .75rem; }
.json-panel { display: grid; gap: .5rem; }
.json-panel h2, .import-preview h3 { margin: 0; color: var(--color-ink); }
.json-panel label { color: var(--color-muted); font-size: .85rem; font-weight: 700; }
.json-panel input[type="file"] { max-width: 100%; min-height: 44px; }
.import-preview { padding: .8rem; background: var(--color-canvas); border-radius: var(--radius-control); }
.import-preview p { margin: .25rem 0; }
.phase-list { display: grid; gap: .5rem; margin: 0; padding: 0; list-style: none; }
.phase-list li { display: flex; justify-content: space-between; align-items: center; gap: .75rem; padding: .7rem; background: var(--color-canvas); border-radius: var(--radius-control); }
.phase-list small { display: block; color: var(--color-muted); }
.sub-editor { padding: .8rem; }
.danger { color: var(--color-danger); }
.session-actions { margin-top: .5rem; }
.plan-picker label { display: block; margin-bottom: .35rem; color: var(--color-muted); font-size: .85rem; font-weight: 700; }
.plan-picker select { width: min(100%, 28rem); background: var(--color-surface); }
.plan-intro { align-items: flex-end; margin-top: 1rem; }
.plan-intro h2, .week-navigation h2 { margin: 0; color: var(--color-ink); }
.plan-intro p { margin: .2rem 0 0; color: var(--color-muted); }
.plan-intro > p { text-align: right; }
.week-section { display: grid; gap: 1rem; }
.week-navigation h2 { text-align: center; }
.week-navigation h2 span { display: block; color: var(--color-muted); font-size: .85rem; font-weight: 500; }
.week-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .65rem; margin: 0; }
.week-metrics > div { min-width: 0; padding: .75rem; background: var(--color-canvas); border-radius: var(--radius-control); }
.week-metrics dt { color: var(--color-muted); font-size: .8rem; }
.week-metrics dd { margin: .2rem 0 0; color: var(--color-ink); font-size: 1.15rem; font-weight: 750; overflow-wrap: anywhere; }
.days { display: grid; grid-template-columns: minmax(0, 1fr); gap: .65rem; margin: 0; padding: 0; list-style: none; }
.day-card { min-width: 0; min-height: 6.5rem; padding: .75rem; background: var(--color-canvas); border: 1px solid var(--color-border); border-radius: var(--radius-control); }
.day-card.is-today { border-color: var(--color-accent-strong); box-shadow: inset 0 0 0 1px var(--color-accent-strong); }
.day-card h3 { margin: 0; color: var(--color-ink); font-size: .9rem; text-transform: capitalize; }
.sessions { display: grid; gap: .5rem; margin: .5rem 0 0; padding: 0; list-style: none; }
.session-card { min-width: 0; padding: .7rem; background: var(--color-surface); border-left: 3px solid var(--color-accent); border-radius: 4px; overflow-wrap: anywhere; }
.session-card h4 { margin: 0; font-size: .95rem; }
.session-card p { margin: .2rem 0 0; }
.session-card .session-target { display: flex; flex-wrap: wrap; gap: .25rem .65rem; color: var(--color-muted); font-size: .82rem; }
.instructions { color: var(--color-muted); font-size: .85rem; }
.day-empty, .week-empty, .empty-state { color: var(--color-muted); }
@media (min-width: 600px) { .week-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } .days, .form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (min-width: 1024px) { .days { grid-template-columns: repeat(7, minmax(0, 1fr)); } .day-card { padding: .6rem; } }
@media (max-width: 480px) { .page-heading, .plan-intro { align-items: stretch; flex-direction: column; } .plan-actions { justify-content: flex-start; } .plan-actions p { text-align: left; } .phase-list li { align-items: flex-start; flex-direction: column; } .week-navigation { gap: .4rem; } .week-navigation h2 { font-size: 1rem; } .week-navigation button { padding: .5rem; } }
</style>
