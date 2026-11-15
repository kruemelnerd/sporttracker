<template>
  <section class="detail">
    <RouterLink class="back-link" to="/">← Zurück zum Dashboard</RouterLink>
    <p v-if="loading" role="status">Laufdetails werden geladen …</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-if="detail && !loading && !error">
      <header class="detail-heading">
        <h1>{{ detail.garminActivityMetrics.activityName ?? 'Lauf' }}</h1>
        <p class="activity-summary">{{ detail.activity.subSport ?? detail.activity.sport }} · {{ date(detail.activity.startTime) }}</p>
        <p class="activity-summary"><strong>{{ distance(detail.activity.distanceMeters) }}</strong> · {{ duration(detail.activity.elapsedSeconds) }} · {{ paceForRun }}</p>
      </header>
      <dl class="metrics" aria-label="Laufkennzahlen">
        <MetricCard v-if="detail.activity.elapsedSeconds !== null" label="Gesamtzeit / verstrichene Zeit" :value="duration(detail.activity.elapsedSeconds)" />
        <MetricCard v-if="detail.activity.averageHeartRate !== null" label="Ø Herzfrequenz" :value="bpm(detail.activity.averageHeartRate)" />
        <MetricCard v-if="detail.maximumHeartRate !== null" label="Max. Herzfrequenz" :value="bpm(detail.maximumHeartRate)" />
        <MetricCard v-if="detail.activity.ascentMeters !== null" label="Höhenmeter bergauf" :value="meters(detail.activity.ascentMeters)" />
        <MetricCard v-if="descent !== null" label="Höhenmeter bergab" :value="meters(descent)" />
        <MetricCard v-if="detail.averageCadence !== null" label="Ø Kadenz" :value="displayCadence(detail.averageCadence)" />
        <MetricCard v-if="detail.movingSeconds !== null" label="Bewegungszeit" :value="duration(detail.movingSeconds)" />
        <MetricCard v-if="detail.calories !== null" label="Kalorien" :value="`${detail.calories} kcal`" />
        <MetricCard v-if="recordValues.minimumAltitudeMeters !== null" label="Min. Höhe" :value="meters(recordValues.minimumAltitudeMeters)" />
        <MetricCard v-if="recordValues.maximumAltitudeMeters !== null" label="Max. Höhe" :value="meters(recordValues.maximumAltitudeMeters)" />
        <MetricCard v-if="recordValues.maximumCadenceStepsPerMinute !== null" label="Max. Kadenz" :value="`${recordValues.maximumCadenceStepsPerMinute} spm`" />
        <MetricCard v-if="paceSpeed.averagePaceSecondsPerKm !== null" label="Ø Pace" :value="formatPace(paceSpeed.averagePaceSecondsPerKm)" />
        <MetricCard v-if="paceSpeed.movingPaceSecondsPerKm !== null" label="Ø Pace in Bewegung" :value="formatPace(paceSpeed.movingPaceSecondsPerKm)" />
        <MetricCard v-if="paceSpeed.bestPaceSecondsPerKm !== null" label="Beste Pace" :value="formatPace(paceSpeed.bestPaceSecondsPerKm)" />
        <MetricCard v-if="paceSpeed.averageSpeedMetersPerSecond !== null" label="Ø Geschwindigkeit" :value="kmh(paceSpeed.averageSpeedMetersPerSecond)" />
        <MetricCard v-if="paceSpeed.movingSpeedMetersPerSecond !== null" label="Ø Geschwindigkeit in Bewegung" :value="kmh(paceSpeed.movingSpeedMetersPerSecond)" />
        <MetricCard v-if="paceSpeed.maximumSpeedMetersPerSecond !== null" label="Max. Geschwindigkeit" :value="kmh(paceSpeed.maximumSpeedMetersPerSecond)" />
      </dl>

      <TrainingSummaryExport :detail="detail" />

      <section v-if="garminMetricsPresent" class="detail-panel" aria-labelledby="garmin-training-heading" data-testid="garmin-training-metrics">
        <h2 id="garmin-training-heading">Trainingsanalyse</h2>
        <dl class="metrics training-metrics">
          <MetricCard v-if="detail.garminTrainingMetrics.aerobicTrainingEffect !== null" label="Aerober Training Effect" :value="garminValue(detail.garminTrainingMetrics.aerobicTrainingEffect)" />
          <MetricCard v-if="detail.garminTrainingMetrics.anaerobicTrainingEffect !== null" label="Anaerober Training Effect" :value="garminValue(detail.garminTrainingMetrics.anaerobicTrainingEffect)" />
          <MetricCard v-if="detail.garminTrainingMetrics.trainingLoad !== null" label="Trainingsbelastung" :value="garminValue(detail.garminTrainingMetrics.trainingLoad)" />
        </dl>
      </section>

      <section class="detail-panel" aria-labelledby="map-heading">
        <h2 id="map-heading">Laufstrecke</h2>
        <div v-if="routeVisible" ref="mapElement" class="map" aria-label="Karte mit GPS-Track"></div>
        <p v-if="!routeVisible">Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.</p>
      </section>

      <section class="detail-panel" aria-labelledby="charts-heading">
        <h2 id="charts-heading">Trainingsverlauf</h2>
        <ul v-if="workoutLegend.length || hasChartTargets" class="phase-legend" aria-label="Trainingsphasen">
          <li v-for="phase in workoutLegend" :key="phase.label">
            <span :style="{ backgroundColor: phase.color }" aria-hidden="true"></span>{{ phase.label }}
          </li>
          <li v-if="hasChartTargets" class="target-legend"><span aria-hidden="true"></span>Trainingsziel</li>
        </ul>
        <p v-if="!charts.pace.length">Für diesen Lauf sind keine Messpunkte vorhanden.</p>
        <div v-else class="charts">
          <div v-if="hasValues(charts.pace)" ref="paceElement" class="chart" data-testid="pace-chart" role="img" :aria-label="`Pace-Diagramm (${charts.pace.filter(([, value]) => value !== null).length} Werte)`"></div>
          <div v-if="hasValues(charts.heartRate)" ref="heartElement" class="chart" data-testid="heart-rate-chart" role="img" aria-label="Herzfrequenz-Diagramm"></div>
          <div v-if="hasValues(charts.elevation)" ref="elevationElement" class="chart" data-testid="elevation-chart" role="img" :aria-label="`Höhenprofil-Diagramm (${charts.elevation.filter(([, value]) => value !== null).length} Werte)`"></div>
          <div v-if="hasValues(charts.cadence)" ref="cadenceElement" class="chart" data-testid="cadence-chart" role="img" aria-label="Kadenz-Diagramm"></div>
          <div v-if="hasValues(charts.stepLength)" ref="stepLengthElement" class="chart" data-testid="step-length-chart" role="img" aria-label="Schrittlänge-Diagramm"></div>
          <div v-if="hasValues(charts.verticalOscillation)" ref="verticalOscillationElement" class="chart" data-testid="vertical-oscillation-chart" role="img" aria-label="Vertikale Bewegung-Diagramm"></div>
          <div v-if="hasValues(charts.verticalRatio)" ref="verticalRatioElement" class="chart" data-testid="vertical-ratio-chart" role="img" aria-label="Vertikales Verhältnis-Diagramm"></div>
          <div v-if="hasValues(charts.groundContactTime)" ref="groundContactTimeElement" class="chart" data-testid="ground-contact-time-chart" role="img" aria-label="Bodenkontaktzeit-Diagramm"></div>
          <div v-if="hasValues(charts.groundContactBalance)" ref="groundContactBalanceElement" class="chart" data-testid="ground-contact-balance-chart" role="img" aria-label="Bodenkontaktbalance-Diagramm"></div>
          <div v-if="hasValues(charts.temperature)" ref="temperatureElement" class="chart" data-testid="temperature-chart" role="img" aria-label="Temperatur-Diagramm"></div>
          <div v-if="hasValues(charts.power)" ref="powerElement" class="chart" data-testid="power-chart" role="img" aria-label="Laufleistung-Diagramm"></div>
        </div>
      </section>

      <section v-if="intervalRows.length" class="detail-panel" aria-labelledby="intervals-heading" data-testid="workout-intervals">
        <h2 id="intervals-heading">Workout-Intervalle</h2>
        <div class="sort-controls">
          <label>Sortieren nach
            <select v-model="intervalSort" aria-label="Workout sortieren nach">
              <option value="execution">Ausführungsreihenfolge</option>
              <option value="step">Workout-Schritt</option>
              <option value="duration">Dauer</option>
              <option value="distance">Distanz</option>
              <option value="pace">Ø Pace</option>
            </select>
          </label>
          <label>Richtung
            <select v-model="intervalDirection" aria-label="Sortierrichtung">
              <option value="asc">Aufsteigend</option>
              <option value="desc">Absteigend</option>
            </select>
          </label>
        </div>
        <p v-if="unmatchedStepCount" class="muted">{{ unmatchedStepCount }} Workout-Schritt(e) ohne zugeordnete Ausführungsrunde werden nicht als ausgeführt dargestellt.</p>
        <div class="table-wrap" role="region" aria-label="Workout-Intervalltabelle; horizontal scrollbar auf kleinen Bildschirmen" tabindex="0">
          <table>
            <thead><tr><th>Nr.</th><th>Trainingsphase</th><th>Wiederholung</th><th>Distanz</th><th>Dauer</th><th>Ø Pace</th><th>Ø HF</th><th>Trainingsziel</th></tr></thead>
            <tbody><tr v-for="interval in sortedIntervalRows" :key="interval.id"
              :data-interval-id="interval.id" :class="{ selected: selectedIntervalId === interval.id }"
              :aria-selected="selectedIntervalId === interval.id" tabindex="0"
              @click="selectInterval(interval.id)" @keydown.enter.prevent="selectInterval(interval.id)"
              @keydown.space.prevent="selectInterval(interval.id)">
              <td>{{ interval.executionOrder + 1 }}</td>
              <td>{{ interval.phaseName }}</td>
              <td>{{ interval.repetitionNumber === null ? '–' : `${interval.repetitionNumber}${interval.repeatBlockCount === null ? '' : ` von ${interval.repeatBlockCount}`}` }}</td>
              <td>{{ distance(interval.distanceMeters) }}</td>
              <td>{{ formatWorkoutDuration(interval.durationSeconds) }}</td>
              <td>{{ formatPace(interval.averagePaceSecondsPerKm) }}</td>
              <td>{{ bpm(interval.averageHeartRate) }}</td>
              <td>{{ formatWorkoutTarget(interval.target) ?? '–' }}</td>
            </tr></tbody>
          </table>
        </div>
        <button type="button" class="reset-zoom" aria-label="Gesamtes Training anzeigen" @click="resetWorkoutSelection">
          Gesamtes Training anzeigen
        </button>
      </section>

      <section v-if="detail.pauses?.length" class="detail-panel" aria-labelledby="pauses-heading" data-testid="timer-pauses">
        <h2 id="pauses-heading">Timer-Pausen</h2>
        <ul><li v-for="(pause, index) in detail.pauses" :key="index">{{ elapsedClock(pause.startElapsedSeconds) }}–{{ elapsedClock(pause.endElapsedSeconds) }}</li></ul>
      </section>

      <section class="detail-panel" aria-labelledby="laps-heading" data-testid="laps">
        <h2 id="laps-heading">Runden</h2>
        <p v-if="!laps.length">Für diesen Lauf sind keine Runden oder zuverlässig ableitbaren Kilometer-Splits vorhanden.</p>
        <div v-else class="table-wrap" role="region" aria-label="Rundentabelle; horizontal scrollbar auf kleinen Bildschirmen" tabindex="0">
          <table>
            <thead><tr><th>Runde</th><th>Distanz</th><th>Dauer</th><th>Ø Pace</th><th>Ø HF</th><th>Höhenmeter +/−</th><th>Ø Kadenz</th></tr></thead>
            <tbody><tr v-for="lap in laps" :key="lap.lapNumber">
              <td>{{ lap.lapNumber }} <small v-if="lap.source === 'derived'">(abgeleitet)</small></td>
              <td>{{ distance(lap.distanceMeters) }}</td><td>{{ duration(lap.durationSeconds) }}</td>
              <td>{{ formatPace(lapPaceSecondsPerKm(lap)) }}</td>
              <td>{{ bpm(lap.averageHeartRate) }}</td><td>{{ meters(lap.ascentMeters) }} / {{ meters(lap.descentMeters) }}</td><td>{{ displayCadence(lap.averageCadence) }}</td>
            </tr></tbody>
          </table>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { connect, init, use as echartsUse, type ECharts } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, MarkAreaComponent, MarkLineComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getActivity, type ActivityDetail } from '../api'
import { activityPaceSpeed, chartSeries, descendedMeters, displayCadence, formatPace, lapPaceSecondsPerKm, lapRows, paceAxis, recordExtrema } from '../activityDetail'
import { createWorkoutIntervals, formatWorkoutDuration, formatWorkoutTarget, normalizeWorkoutTarget, sortWorkoutIntervals, workoutLegend as getWorkoutLegend, workoutPhaseAreas, workoutTargetMarks, workoutTooltipLabel, workoutZoomExtent, type WorkoutDirection, type WorkoutMetric, type WorkoutSort } from '../workoutIntervals'
import MetricCard from '../components/MetricCard.vue'
import TrainingSummaryExport from '../components/TrainingSummaryExport.vue'

echartsUse([LineChart, DataZoomComponent, GridComponent, MarkAreaComponent, MarkLineComponent, TitleComponent, TooltipComponent, CanvasRenderer])

const currentRoute = useRoute()
const detail = ref<ActivityDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const mapElement = ref<HTMLElement | null>(null)
const paceElement = ref<HTMLElement | null>(null)
const heartElement = ref<HTMLElement | null>(null)
const elevationElement = ref<HTMLElement | null>(null)
const cadenceElement = ref<HTMLElement | null>(null)
const stepLengthElement = ref<HTMLElement | null>(null)
const verticalOscillationElement = ref<HTMLElement | null>(null)
const verticalRatioElement = ref<HTMLElement | null>(null)
const groundContactTimeElement = ref<HTMLElement | null>(null)
const groundContactBalanceElement = ref<HTMLElement | null>(null)
const temperatureElement = ref<HTMLElement | null>(null)
const powerElement = ref<HTMLElement | null>(null)
const intervalSort = ref<WorkoutSort>('execution')
const intervalDirection = ref<WorkoutDirection>('asc')
const selectedIntervalId = ref<string | null>(null)
let map: ReturnType<typeof L.map> | null = null
let chartInstances: ECharts[] = []
const chartMetrics = new WeakMap<ECharts, WorkoutMetric | null>()
let chartResizeObserver: ResizeObserver | null = null
let requestId = 0

const routePoints = computed(() => (detail.value?.records ?? []).filter((record) => record.latitude !== null && record.longitude !== null))
const routeCoords = computed(() => routePoints.value.map((point) => [Number(point.latitude), Number(point.longitude)] as [number, number]))
const routeVisible = computed(() => routeCoords.value.length >= 2)
const routePointsForMap = computed(() => routeVisible.value ? routeCoords.value : [])
const charts = computed(() => chartSeries(detail.value?.records ?? []))
const laps = computed(() => lapRows(detail.value?.laps ?? [], detail.value?.records ?? []))
const workoutData = computed(() => createWorkoutIntervals(detail.value?.workoutSteps ?? [], detail.value?.laps ?? []))
const intervalRows = computed(() => workoutData.value.intervals)
const sortedIntervalRows = computed(() => sortWorkoutIntervals(intervalRows.value, intervalSort.value, intervalDirection.value))
const unmatchedStepCount = computed(() => workoutData.value.unmatchedStepCount)
const workoutLegend = computed(() => getWorkoutLegend(intervalRows.value))
const intervalAreas = computed(() => workoutPhaseAreas(intervalRows.value, charts.value.coordinate === 'distance' ? 'distance' : 'time'))
const chartAxis = computed(() => charts.value.coordinate === 'distance' ? 'distance' as const : 'time' as const)
const chartDomain = computed(() => {
  const values = (detail.value?.records ?? []).map((record) =>
    chartAxis.value === 'distance' ? record.distanceMeters : record.elapsedSeconds)
    .filter((value): value is number => value !== null && Number.isFinite(value))
  return values.length ? values.reduce((domain, value) => ({
    start: Math.min(domain.start, value),
    end: Math.max(domain.end, value),
  }), { start: values[0], end: values[0] }) : null
})
const selectedInterval = computed(() => intervalRows.value.find(({ id }) => id === selectedIntervalId.value) ?? null)
const hasChartTargets = computed(() => intervalRows.value.some((interval) => {
  const target = normalizeWorkoutTarget(interval.target)
  return target?.metric !== null && target?.metric !== undefined && (target.low !== null || target.high !== null)
}))
const intervalAt = (x: number) => [...intervalRows.value].reverse().find((interval) => {
  const start = charts.value.coordinate === 'distance' ? interval.startDistanceMeters : interval.startElapsedSeconds
  const end = charts.value.coordinate === 'distance' ? interval.endDistanceMeters : interval.endElapsedSeconds
  return start !== null && end !== null && x >= start && x <= end
})
const paceSpeed = computed(() => detail.value ? activityPaceSpeed(detail.value) : { averageSpeedMetersPerSecond: null, movingSpeedMetersPerSecond: null, maximumSpeedMetersPerSecond: null, averagePaceSecondsPerKm: null, movingPaceSecondsPerKm: null, bestPaceSecondsPerKm: null })
const recordValues = computed(() => recordExtrema(detail.value?.records ?? []))
const paceForRun = computed(() => {
  return formatPace(paceSpeed.value.averagePaceSecondsPerKm)
})
const descent = computed(() => {
  if (detail.value?.laps.length && detail.value.laps.every((lap) => lap.descentMeters !== null)) return detail.value.laps.reduce((sum, lap) => sum + lap.descentMeters!, 0)
  return descendedMeters(detail.value?.records ?? [])
})
const garminMetricsPresent = computed(() => Object.values(detail.value?.garminTrainingMetrics ?? {}).some((value) => value !== null))
const hasValues = (series: [number, number | null][]) => series.some(([, value]) => value !== null)

function selectInterval(id: string, scrollIntoView = false) {
  const interval = intervalRows.value.find(({ id: intervalId }) => intervalId === id)
  if (!interval) return
  selectedIntervalId.value = id
  const extent = chartDomain.value ? workoutZoomExtent(interval, chartAxis.value, chartDomain.value) : null
  if (extent) chartInstances.forEach((chart) => chart.dispatchAction({ type: 'dataZoom', startValue: extent.start, endValue: extent.end }))
  updateChartHighlights()
  if (scrollIntoView) void nextTick(() => {
    const row = document.querySelector<HTMLElement>(`[data-interval-id="${id}"]`)
    if (!row) return
    const bounds = row.getBoundingClientRect()
    if (bounds.top < 0 || bounds.bottom > window.innerHeight) row.scrollIntoView({ block: 'nearest' })
  })
}

function resetWorkoutSelection() {
  selectedIntervalId.value = null
  if (chartDomain.value) chartInstances.forEach((chart) => chart.dispatchAction({
    type: 'dataZoom', startValue: chartDomain.value!.start, endValue: chartDomain.value!.end,
  }))
  updateChartHighlights()
}

function targetMarkAreas(metric: WorkoutMetric | null) {
  const areas: Array<unknown> = intervalAreas.value.map(({ start, end, color }) =>
    [{ xAxis: start, itemStyle: { color } }, { xAxis: end }])
  if (metric) workoutTargetMarks(intervalRows.value, chartAxis.value, metric)
    .filter(({ low, high }) => low !== high)
    .forEach(({ start, end, low, high }) => areas.push([
      { xAxis: start, yAxis: low, itemStyle: { color: 'rgba(107, 114, 128, 0.10)' } },
      { xAxis: end, yAxis: high },
    ]))
  const selected = selectedInterval.value
  if (selected) {
    const start = chartAxis.value === 'distance' ? selected.startDistanceMeters : selected.startElapsedSeconds
    const end = chartAxis.value === 'distance' ? selected.endDistanceMeters : selected.endElapsedSeconds
    if (start !== null && end !== null) areas.push([
      { xAxis: start, yAxis: 'min', itemStyle: { color: 'rgba(37, 99, 235, 0.10)' } },
      { xAxis: end, yAxis: 'max' },
    ])
  }
  return areas
}

function targetMarkLines(metric: WorkoutMetric | null) {
  if (!metric) return []
  return workoutTargetMarks(intervalRows.value, chartAxis.value, metric).flatMap(({ start, end, low, high }) =>
    [...new Set([low, high])].map((value) => [
      { coord: [start, value] },
      { coord: [end, value] },
    ]))
}

function updateChartHighlights() {
  chartInstances.forEach((chart) => {
    chart.setOption({ series: [{ markArea: { data: targetMarkAreas(chartMetrics.get(chart) ?? null) } }] })
  })
}

const distance = (meters: number | null) => meters === null ? '–' : `${(meters / 1000).toFixed(2)} km`
const duration = (seconds: number | null) => {
  if (seconds === null) return '–'
  const minutes = Math.round(seconds / 60)
  return `${Math.floor(minutes / 60) ? `${Math.floor(minutes / 60)} h ` : ''}${String(minutes % 60).padStart(2, '0')} min`
}
const meters = (value: number | null) => value === null ? '–' : `${Math.round(value)} m`
const kmh = (value: number) => `${new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(value * 3.6)} km/h`
const bpm = (value: number | null) => value === null ? '–' : `${value} bpm`
const garminValue = (value: number) => new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(value)
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'short' }).format(new Date(value))
const elapsedClock = (seconds: number) => {
  const total = Math.max(0, Math.floor(seconds))
  return `${String(Math.floor(total / 3600)).padStart(2, '0')}:${String(Math.floor(total / 60) % 60).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

async function loadActivity(id: string) {
  const currentRequest = ++requestId
  disposeVisuals()
  selectedIntervalId.value = null
  loading.value = true
  error.value = null
  try {
    const activity = await getActivity(id)
    if (currentRequest !== requestId) return
    detail.value = activity
    loading.value = false
    await nextTick()
    if (currentRequest !== requestId) return
    if (routePointsForMap.value.length >= 2 && mapElement.value) {
      map = L.map(mapElement.value).fitBounds(routePointsForMap.value, { padding: [20, 20] })
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap contributors' }).addTo(map)
      L.polyline(routePointsForMap.value, { color: '#2563eb', weight: 4 }).addTo(map)
      L.marker(routePointsForMap.value[0]).addTo(map).bindTooltip('Start')
      L.marker(routePointsForMap.value.at(-1)!).addTo(map).bindTooltip('Ziel')
    }
    if (charts.value.pace.length) {
      if (hasValues(charts.value.pace) && paceElement.value) chartInstances.push(makeChart(paceElement.value, 'Pace', charts.value.pace, 'min/km', 'pace', true))
      if (hasValues(charts.value.heartRate) && heartElement.value) chartInstances.push(makeChart(heartElement.value, 'Herzfrequenz', charts.value.heartRate, 'bpm', 'heartRate'))
      if (hasValues(charts.value.elevation) && elevationElement.value) chartInstances.push(makeChart(elevationElement.value, 'Höhenprofil', charts.value.elevation, 'm'))
      if (hasValues(charts.value.cadence) && cadenceElement.value) chartInstances.push(makeChart(cadenceElement.value, 'Kadenz', charts.value.cadence.map(([x, value]) => [x, value === null ? null : value * 2]), 'steps/min', 'cadence'))
      if (hasValues(charts.value.stepLength) && stepLengthElement.value) chartInstances.push(makeChart(stepLengthElement.value, 'Schrittlänge', charts.value.stepLength, 'm'))
      if (hasValues(charts.value.verticalOscillation) && verticalOscillationElement.value) chartInstances.push(makeChart(verticalOscillationElement.value, 'Vertikale Bewegung', charts.value.verticalOscillation, 'cm'))
      if (hasValues(charts.value.verticalRatio) && verticalRatioElement.value) chartInstances.push(makeChart(verticalRatioElement.value, 'Vertikales Verhältnis', charts.value.verticalRatio, '%'))
      if (hasValues(charts.value.groundContactTime) && groundContactTimeElement.value) chartInstances.push(makeChart(groundContactTimeElement.value, 'Bodenkontaktzeit', charts.value.groundContactTime, 'ms'))
      if (hasValues(charts.value.groundContactBalance) && groundContactBalanceElement.value) chartInstances.push(makeChart(groundContactBalanceElement.value, 'Bodenkontaktbalance', charts.value.groundContactBalance, '%'))
      if (hasValues(charts.value.temperature) && temperatureElement.value) chartInstances.push(makeChart(temperatureElement.value, 'Temperatur', charts.value.temperature, '°C'))
      if (hasValues(charts.value.power) && powerElement.value) chartInstances.push(makeChart(powerElement.value, 'Laufleistung', charts.value.power, 'W', 'power'))
      if (chartInstances.length > 1) connect(chartInstances)
      chartResizeObserver = new ResizeObserver((entries) => {
        const changedElements = new Set(entries.map(({ target }) => target))
        chartInstances.filter((chart) => changedElements.has(chart.getDom())).forEach((chart) => chart.resize())
      })
      chartInstances.forEach((chart) => chartResizeObserver?.observe(chart.getDom()))
    }
  } catch (requestError) {
    if (currentRequest !== requestId) return
    error.value = requestError instanceof Error ? requestError.message : 'Laufdetails konnten nicht geladen werden.'
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

function makeChart(
  element: HTMLElement,
  name: string,
  data: [number, number | null][],
  unit: string,
  metric: WorkoutMetric | null = null,
  inverse = false,
) {
  const chart = init(element)
  chartMetrics.set(chart, metric)
  chart.setOption({
    animation: false,
    title: { text: name, left: 12, top: 8, textStyle: { fontSize: 15 } },
    grid: { left: 58, right: 20, top: 42, bottom: 32 },
      tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: (items: Array<{ data: [number, number | null] }>) => {
        const [x, y] = items[0].data
        const interval = intervalAt(x)
        const phase = interval ? workoutTooltipLabel(interval) : null
        const target = interval ? normalizeWorkoutTarget(interval.target) : null
        let targetLabel = target?.label ?? null
        if (target?.metric === 'pace' && target.low !== null && target.high !== null) {
          const low = formatPace(target.low).replace(' min/km', '')
          const high = formatPace(target.high).replace(' min/km', '')
          targetLabel = low === high ? `${low} min/km` : `${low}–${high} min/km`
        } else if (targetLabel?.includes(': ')) {
          targetLabel = targetLabel.slice(targetLabel.indexOf(': ') + 2)
        }
        const actual = y === null ? '–' : unit === 'min/km' ? formatPace(y) : `${y} ${unit}`
        return `${charts.value.coordinate === 'distance' ? `${(x / 1000).toFixed(2)} km` : elapsedClock(x)}${phase ? `<br>${phase}` : ''}<br>Ist: ${actual}${target && metric !== null && target.metric === metric ? `<br>Ziel: ${targetLabel}` : ''}`
       } },
     xAxis: { type: 'value', name: charts.value.axisName, axisLabel: { formatter: (value: number) => charts.value.coordinate === 'distance' ? (value / 1000).toFixed(1) : elapsedClock(value) } },
     yAxis: { type: 'value', name: unit, inverse, scale: true, axisLabel: { formatter: (value: number) => unit === 'min/km' ? paceAxis(value) : value } },
     dataZoom: [{ type: 'inside', xAxisIndex: 0 }, { type: 'slider', xAxisIndex: 0, height: 16, bottom: 4 }],
      series: [{
        type: 'line', name, data, sampling: 'lttb', showSymbol: false, connectNulls: false, smooth: false, lineStyle: { width: 2 },
        markArea: { silent: true, itemStyle: { opacity: 1 }, data: targetMarkAreas(metric) },
        markLine: {
          silent: true, symbol: 'none',
          lineStyle: { color: '#6B7280', type: 'dashed', width: 1 },
          data: targetMarkLines(metric),
        },
      }],
   })
  chart.getZr().on('click', (event) => {
    if (!chart.containPixel({ gridIndex: 0 }, [event.offsetX, event.offsetY])) return
    const [x] = chart.convertFromPixel({ gridIndex: 0 }, [event.offsetX, event.offsetY]) as number[]
    if (Number.isFinite(x)) {
      const interval = intervalAt(x)
      if (interval) selectInterval(interval.id, true)
    }
  })
  return chart
}

function disposeVisuals() {
  chartResizeObserver?.disconnect()
  chartResizeObserver = null
  map?.remove()
  map = null
  chartInstances.forEach((chart) => chart.dispose())
  chartInstances = []
}

onMounted(async () => {
  await loadActivity(String(currentRoute.params.id))
})
watch(() => currentRoute.params.id, (id) => id && loadActivity(String(id)))
onUnmounted(() => { requestId++; disposeVisuals() })
</script>

<style scoped>
.detail { display: grid; gap: 1.5rem; min-width: 0; }
.back-link { min-height: 44px; display: inline-flex; align-items: center; width: fit-content; }
.detail-heading h1 { margin: 0; color: var(--color-ink); font-size: clamp(1.5rem, 3vw, 2rem); line-height: 1.2; }
.activity-summary { margin: .5rem 0 0; color: var(--color-muted); }
.activity-summary strong { color: var(--color-ink); font-size: 1.125rem; }
.metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .75rem; margin: 0; }
.detail-panel { min-width: 0; padding: 1.25rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-card); }
.detail-panel h2 { margin: 0 0 1rem; color: var(--color-ink); font-size: 1.125rem; }
.map { width: 100%; min-height: 24rem; border-radius: var(--radius-control); }
.charts { display: grid; grid-template-columns: 1fr; gap: 1rem; }
.phase-legend { display: flex; flex-wrap: wrap; gap: .5rem 1rem; list-style: none; margin: 0 0 1rem; padding: 0; }
.phase-legend li { display: inline-flex; align-items: center; gap: .4rem; color: var(--color-muted); font-size: .875rem; }
.phase-legend span { width: .9rem; height: .9rem; border: 1px solid var(--color-border); border-radius: 2px; }
.phase-legend .target-legend span { background: rgba(107, 114, 128, .1); border: 1px dashed #6b7280; }
.chart { width: 100%; min-width: 0; height: 17rem; }
.sort-controls { display: flex; flex-wrap: wrap; gap: .75rem; margin-bottom: 1rem; }
.sort-controls label { display: grid; gap: .25rem; color: var(--color-muted); font-size: .875rem; }
.sort-controls select { min-height: 2.75rem; max-width: 100%; padding: .5rem; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-ink); }
.reset-zoom { min-height: 2.75rem; margin-top: 1rem; padding: .5rem .75rem; border: 1px solid var(--color-border); border-radius: var(--radius-control); background: var(--color-surface); color: var(--color-ink); cursor: pointer; }
tbody tr { cursor: pointer; }
tbody tr:hover, tbody tr:focus-visible { background: var(--color-surface-muted, #f3f4f6); outline: 2px solid var(--color-primary, #2563eb); outline-offset: -2px; }
tbody tr.selected { background: rgba(37, 99, 235, .1); }
.muted { color: var(--color-muted); }
.table-wrap { max-width: 100%; overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: .75rem; text-align: left; border-bottom: 1px solid var(--color-border); white-space: nowrap; }
th { color: var(--color-muted); font-size: .8125rem; }
@media (min-width: 1024px) { .metrics { grid-template-columns: repeat(7, minmax(0, 1fr)); } }
@media (max-width: 767px) {
  .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .detail-panel { padding: 1rem; }
  .map { min-height: 18rem; }
  .chart { height: 15rem; }
}
</style>
