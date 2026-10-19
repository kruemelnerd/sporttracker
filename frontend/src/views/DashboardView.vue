<template>
  <section class="dashboard">
    <header class="dashboard-header">
      <div><h1>Letzte sechs Wochen</h1><p>Deine Lauftrainings im Überblick</p></div>
      <RouterLink class="import-action" to="/import">FIT-Dateien importieren</RouterLink>
    </header>

    <p v-if="loading" role="status">Dashboard wird geladen …</p>
    <p v-else-if="error" role="alert">Dashboard konnte nicht geladen werden: {{ error }}</p>
    <p v-else-if="!summary?.activities.length" data-testid="empty-state">Keine Läufe in den letzten sechs Wochen. Importiere eine FIT-Datei, um hier deine Trainings zu sehen.</p>
    <template v-else>
      <dl class="metrics" aria-label="Zusammenfassung der letzten sechs Wochen">
        <MetricCard label="Gesamtdistanz" :value="distance(summary.metrics.distanceMeters)" value-test-id="total-distance" />
        <MetricCard label="Läufe" :value="String(summary.metrics.runCount)" value-test-id="run-count" />
        <MetricCard label="Trainingszeit" :value="duration(summary.metrics.durationSeconds)" value-test-id="total-time" />
        <MetricCard label="Ø Distanz / Lauf" :value="distance(summary.metrics.averageDistanceMeters)" value-test-id="average-distance" />
        <MetricCard label="Ø Pace" :value="pace(summary.metrics.paceSecondsPerKm)" value-test-id="average-pace" emphasis />
        <MetricCard label="Höhenmeter" :value="elevation(summary.metrics.ascentMeters, summary.metrics.ascentIncomplete)" value-test-id="total-ascent" />
      </dl>

      <section class="week-panels" aria-label="Wochenübersicht der letzten sechs Wochen">
        <section class="panel" aria-labelledby="distance-weeks-title">
          <h2 id="distance-weeks-title">Wochenvolumen</h2>
          <ol class="week-chart" aria-label="Distanz je Woche">
            <li v-for="(week, index) in summary.weeks" :key="`distance-${week.start}`" :data-testid="`week-bar-${index}`">
              <span class="week-bar-track" aria-hidden="true"><span class="week-bar" :style="{ height: barHeight(week.distanceMeters, maxWeeklyDistance) }" /></span>
              <strong>W{{ index + 1 }}</strong><span>{{ distance(week.distanceMeters) }}</span>
            </li>
          </ol>
        </section>
        <section class="panel" aria-labelledby="runs-weeks-title">
          <h2 id="runs-weeks-title">Läufe pro Woche</h2>
          <ol class="week-chart run-count-chart" aria-label="Anzahl der Läufe je Woche">
            <li v-for="(week, index) in summary.weeks" :key="`runs-${week.start}`">
              <span class="week-bar-track" aria-hidden="true"><span class="week-bar" :style="{ height: barHeight(week.runCount, maxWeeklyRuns) }" /></span>
              <strong>W{{ index + 1 }}</strong><span>{{ week.runCount }} {{ week.runCount === 1 ? 'Lauf' : 'Läufe' }}</span>
            </li>
          </ol>
        </section>
      </section>

      <section class="weeks" aria-labelledby="weeks-title">
        <h2 id="weeks-title" class="sr-only">Wochenwerte</h2>
        <ol>
          <li v-for="(week, index) in summary.weeks" :key="week.start" :data-testid="`week-${index}`">
            <strong>Woche {{ index + 1 }}</strong><span>{{ distance(week.distanceMeters) }}</span>
            <span>{{ duration(week.durationSeconds) }}</span><span>{{ week.runCount }} {{ week.runCount === 1 ? 'Lauf' : 'Läufe' }}</span>
          </li>
        </ol>
      </section>

      <section class="runs-section" aria-labelledby="runs-title">
        <h3 id="runs-title">Läufe</h3>
        <ol class="runs">
          <li v-for="run in summary.activities" :key="run.id">
            <RouterLink :to="`/activities/${run.id}`" :data-testid="`activity-${run.id}`">
              <ActivityTypeIcon :sub-sport="run.subSport" />
              <span class="run-description">
                <strong>{{ trainingType(run.subSport) }}</strong>
                <time :datetime="run.startTime">{{ date(run.startTime) }}</time>
                <span>{{ distance(run.distanceMeters) }}</span>
              </span>
              <span class="run-metrics">
                <span>{{ duration(run.elapsedSeconds) }}</span>
                <strong>{{ paceForRun(run) }}</strong>
                <span>{{ heartRate(run.averageHeartRate) }}</span>
              </span>
            </RouterLink>
          </li>
        </ol>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getActivities } from '../api'
import { RouterLink } from 'vue-router'
import { sixWeekStart, summarizeActivities, type Activity } from '../dashboard'
import MetricCard from '../components/MetricCard.vue'
import ActivityTypeIcon from '../components/ActivityTypeIcon.vue'

const loading = ref(true)
const error = ref<string | null>(null)
const summary = ref<ReturnType<typeof summarizeActivities> | null>(null)

const distance = (meters: number | null) => meters === null ? '–' : `${(meters / 1000).toFixed(2)} km`
const duration = (seconds: number | null) => {
  if (seconds === null) return '–'
  const minutes = Math.round(seconds / 60)
  const hours = Math.floor(minutes / 60)
  return hours ? `${hours} h ${String(minutes % 60).padStart(2, '0')} min` : `${minutes} min`
}
const pace = (secondsPerKm: number | null) => {
  if (secondsPerKm === null) return '–'
  const totalSeconds = Math.round(secondsPerKm)
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')} min/km`
}
const elevation = (meters: number | null, incomplete: boolean) => incomplete ? 'Unvollständig' : meters === null ? '–' : `${Math.round(meters)} m`
const heartRate = (bpm: number | null) => bpm === null ? '–' : `${bpm} bpm`
const trainingType = (subSport: string | null) => subSport ? subSport.replaceAll('_', ' ') : 'Lauf'
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const paceForRun = (run: Activity) => run.elapsedSeconds === null || run.distanceMeters === null || run.distanceMeters <= 0
  ? '–'
  : pace(run.elapsedSeconds / (run.distanceMeters / 1000))
const maxWeeklyDistance = computed(() => Math.max(0, ...(summary.value?.weeks.map(({ distanceMeters }) => distanceMeters ?? 0) ?? [])))
const maxWeeklyRuns = computed(() => Math.max(0, ...(summary.value?.weeks.map(({ runCount }) => runCount) ?? [])))
const barHeight = (value: number | null, max: number) => `${value === null || max === 0 ? 0 : Math.max(6, value / max * 100)}%`

onMounted(async () => {
  const now = new Date()
  try {
    const activities = await getActivities(sixWeekStart(now).toISOString())
    summary.value = summarizeActivities(activities, now)
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : 'Unbekannter Fehler'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.dashboard { display: grid; gap: 1.5rem; }
.dashboard-header { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.dashboard-header h1 { margin: 0; color: var(--color-ink); font-size: clamp(1.5rem, 3vw, 2rem); line-height: 1.2; }
.dashboard-header p { margin: .5rem 0 0; color: var(--color-muted); }
.import-action { min-height: 44px; display: inline-flex; align-items: center; padding: .625rem 1rem; color: white; background: var(--color-accent-strong); border-radius: var(--radius-control); font-weight: 600; text-decoration: none; }
.metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .75rem; margin: 0; }
.week-panels { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
.panel { min-width: 0; padding: 1rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-card); }
.panel h2, .runs-section h2 { margin: 0 0 1rem; color: var(--color-ink); font-size: 1.125rem; }
.week-chart { height: 10rem; display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); align-items: end; gap: .5rem; margin: 0; padding: 0; list-style: none; }
.week-chart li { min-width: 0; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: end; gap: .25rem; text-align: center; font-size: .75rem; }
.week-bar-track { width: min(100%, 2.25rem); height: 5.5rem; display: flex; align-items: end; overflow: hidden; background: #edf2f6; border-radius: .375rem .375rem 0 0; }
.week-bar { width: 100%; min-height: 0; background: var(--color-accent); border-radius: .375rem .375rem 0 0; }
.run-count-chart .week-bar { background: #7f91a4; }
.weeks ol, .runs { display: grid; gap: .5rem; margin: 0; padding: 0; list-style: none; }
.weeks ol { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.weeks li, .runs li { min-width: 0; padding: .75rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-card); }
.weeks li { display: grid; gap: .25rem; }
.runs-section h2 { margin-bottom: .75rem; }
.runs a { min-height: 4.25rem; display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: .75rem; align-items: center; color: inherit; text-decoration: none; }
.runs a:hover { background: var(--color-accent-subtle); }
.run-description, .run-metrics { min-width: 0; display: flex; flex-wrap: wrap; gap: .25rem .75rem; align-items: baseline; }
.run-description strong { width: 100%; color: var(--color-ink); }
.run-description time, .run-metrics span { color: var(--color-muted); font-size: .875rem; }
.run-metrics { justify-content: flex-end; }
.run-metrics strong { color: var(--color-accent-strong); white-space: nowrap; }
@media (min-width: 1024px) {
  .metrics { grid-template-columns: repeat(6, minmax(0, 1fr)); }
  .weeks ol { grid-template-columns: repeat(6, minmax(0, 1fr)); }
}
@media (max-width: 767px) {
  .dashboard-header { align-items: flex-start; flex-direction: column; }
  .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .week-panels { grid-template-columns: 1fr; }
  .weeks ol { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .runs a { grid-template-columns: auto minmax(0, 1fr); }
  .run-metrics { grid-column: 2; justify-content: flex-start; }
}
</style>
