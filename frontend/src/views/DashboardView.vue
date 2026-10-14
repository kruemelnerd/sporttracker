<template>
  <section class="dashboard">
    <header class="dashboard-header">
      <div><h2>Letzte sechs Wochen</h2><p>Deine Lauftrainings im Überblick</p></div>
      <RouterLink to="/import">FIT-Dateien importieren</RouterLink>
    </header>

    <p v-if="loading" role="status">Dashboard wird geladen …</p>
    <p v-else-if="error" role="alert">Dashboard konnte nicht geladen werden: {{ error }}</p>
    <p v-else-if="!summary?.activities.length" data-testid="empty-state">Keine Läufe in den letzten sechs Wochen. Importiere eine FIT-Datei, um hier deine Trainings zu sehen.</p>
    <template v-else>
      <dl class="metrics" aria-label="Zusammenfassung der letzten sechs Wochen">
        <div><dt>Gesamtdistanz</dt><dd data-testid="total-distance">{{ distance(summary.metrics.distanceMeters) }}</dd></div>
        <div><dt>Läufe</dt><dd data-testid="run-count">{{ summary.metrics.runCount }}</dd></div>
        <div><dt>Trainingszeit</dt><dd data-testid="total-time">{{ duration(summary.metrics.durationSeconds) }}</dd></div>
        <div><dt>Ø Distanz / Lauf</dt><dd data-testid="average-distance">{{ distance(summary.metrics.averageDistanceMeters) }}</dd></div>
        <div><dt>Ø Pace</dt><dd data-testid="average-pace">{{ pace(summary.metrics.paceSecondsPerKm) }}</dd></div>
        <div><dt>Höhenmeter</dt><dd data-testid="total-ascent">{{ elevation(summary.metrics.ascentMeters) }}</dd></div>
      </dl>

      <section class="weeks" aria-labelledby="weeks-title">
        <h3 id="weeks-title">Wochenübersicht</h3>
        <ol>
          <li v-for="(week, index) in summary.weeks" :key="week.start" :data-testid="`week-${index}`">
            <strong>Woche {{ index + 1 }}</strong><span>{{ distance(week.distanceMeters) }}</span>
            <span>{{ duration(week.durationSeconds) }}</span><span>{{ week.runCount }} {{ week.runCount === 1 ? 'Lauf' : 'Läufe' }}</span>
          </li>
        </ol>
      </section>

      <section aria-labelledby="runs-title">
        <h3 id="runs-title">Läufe</h3>
        <ol class="runs">
          <li v-for="run in summary.activities" :key="run.id">
            <RouterLink :to="`/activities/${run.id}`" :data-testid="`activity-${run.id}`">
              <time :datetime="run.startTime">{{ date(run.startTime) }}</time>
              <span>{{ trainingType(run.subSport) }}</span>
              <span>{{ distance(run.distanceMeters) }}</span>
              <span>{{ duration(run.elapsedSeconds) }}</span>
              <span>{{ paceForRun(run) }}</span>
              <span>{{ heartRate(run.averageHeartRate) }}</span>
            </RouterLink>
          </li>
        </ol>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getActivities } from '../api'
import { RouterLink } from 'vue-router'
import { sixWeekStart, summarizeActivities, type Activity } from '../dashboard'

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
const elevation = (meters: number | null) => meters === null ? '–' : `${Math.round(meters)} m`
const heartRate = (bpm: number | null) => bpm === null ? '–' : `${bpm} bpm`
const trainingType = (subSport: string | null) => subSport ? subSport.replaceAll('_', ' ') : 'Lauf'
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const paceForRun = (run: Activity) => run.elapsedSeconds === null || run.distanceMeters === null || run.distanceMeters <= 0
  ? '–'
  : pace(run.elapsedSeconds / (run.distanceMeters / 1000))

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
.dashboard { max-width: 72rem; margin: 0 auto; padding: 1rem; }
.dashboard-header { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.dashboard-header p { margin-top: 0; }
.metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .75rem; }
.metrics div, .weeks li, .runs li { border: 1px solid #d8dde3; border-radius: .5rem; padding: .75rem; }
.metrics dt { color: #53606d; font-size: .9rem; }
.metrics dd { font-size: 1.25rem; font-weight: 650; margin: .25rem 0 0; }
.weeks ol, .runs { list-style: none; padding: 0; display: grid; gap: .5rem; }
.weeks ol { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.weeks li { display: grid; gap: .25rem; }
.runs a { color: inherit; text-decoration: none; display: grid; grid-template-columns: 1.5fr 1fr repeat(4, 1fr); gap: .5rem; align-items: center; }
.runs a:hover { text-decoration: underline; }
@media (max-width: 600px) {
  .dashboard-header { align-items: flex-start; flex-direction: column; }
  .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .weeks ol { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .runs a { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
