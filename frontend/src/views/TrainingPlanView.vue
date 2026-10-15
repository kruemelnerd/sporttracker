<template>
  <section class="training-plan" aria-labelledby="training-plan-title">
    <header class="page-heading">
      <div><p class="eyebrow">DEIN TRAININGSPLAN</p><h1 id="training-plan-title">Trainingsplan</h1></div>
    </header>

    <p v-if="loading" role="status">Trainingspläne werden geladen …</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <p v-else-if="!plans.length" class="empty-state">Noch kein Trainingsplan vorhanden. Erstelle einen Plan, um deine Wochen zu organisieren.</p>

    <template v-else-if="plan">
      <div class="plan-picker panel">
        <label for="plan-select">Gewählter Trainingsplan</label>
        <select id="plan-select" v-model="selectedId" @change="selectPlan(selectedId)">
          <option v-for="item in plans" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <div class="plan-intro">
          <div><h2>{{ plan.name }}</h2><p>{{ plan.goal }}<span v-if="plan.raceDate"> · Wettkampf am {{ formatDate(plan.raceDate) }}</span></p></div>
          <p><strong>Aktuelle Phase:</strong> {{ phase?.name ?? 'Keine Phase' }}</p>
        </div>
      </div>

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
  type PlannedSession,
  type TrainingPlan,
  type TrainingPlanSummary,
} from '../trainingPlansApi'
import { initialPlanWeek, isoWeekLabel, phaseForWeek, shiftWeek, summarizeWeek } from '../trainingPlan'

const loading = ref(true)
const sessionsLoading = ref(false)
const error = ref('')
const plans = ref<TrainingPlanSummary[]>([])
const selectedId = ref('')
const plan = ref<TrainingPlan | null>(null)
const sessions = ref<PlannedSession[]>([])
const weekStart = ref('')
const today = localDateString(new Date())
const firstWeek = computed(() => plan.value ? monday(plan.value.startDate) : '')
const lastWeek = computed(() => plan.value ? monday(plan.value.endDate) : '')
const weekEnd = computed(() => addDays(weekStart.value, 6))
const phase = computed(() => plan.value ? phaseForWeek(plan.value.phases, weekStart.value) : null)
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

watch(weekStart, async (week) => {
  if (!week || !plan.value) return
  sessionsLoading.value = true
  error.value = ''
  try {
    sessions.value = await getPlannedSessions(plan.value.id, week, weekEnd.value)
  } catch (cause) {
    error.value = message(cause)
  } finally {
    sessionsLoading.value = false
  }
})

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
function monday(value: string) {
  const date = localDate(value)
  date.setDate(date.getDate() - (date.getDay() + 6) % 7)
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
@media (min-width: 600px) { .week-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } .days { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (min-width: 1024px) { .days { grid-template-columns: repeat(7, minmax(0, 1fr)); } .day-card { padding: .6rem; } }
@media (max-width: 480px) { .page-heading, .plan-intro { align-items: stretch; flex-direction: column; } .plan-intro > p { text-align: left; } .week-navigation { gap: .4rem; } .week-navigation h2 { font-size: 1rem; } .week-navigation button { padding: .5rem; } }
</style>
