<template>
  <section class="detail">
    <RouterLink class="back-link" to="/">← Zurück zum Dashboard</RouterLink>
    <p v-if="loading" role="status">Laufdetails werden geladen …</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-if="detail && !loading && !error">
      <header class="detail-heading">
        <h1>{{ date(detail.activity.startTime) }}</h1>
        <p class="activity-summary"><strong>{{ distance(detail.activity.distanceMeters) }}</strong> · {{ duration(detail.activity.elapsedSeconds) }} · {{ paceForRun }}</p>
      </header>
      <dl class="metrics" aria-label="Laufkennzahlen">
        <MetricCard label="Ø Herzfrequenz" :value="bpm(detail.activity.averageHeartRate)" />
        <MetricCard label="Max. Herzfrequenz" :value="bpm(detail.maximumHeartRate)" />
        <MetricCard label="Höhenmeter bergauf" :value="meters(detail.activity.ascentMeters)" />
        <MetricCard label="Höhenmeter bergab" :value="meters(descent)" />
        <MetricCard label="Ø Kadenz" :value="displayCadence(detail.averageCadence)" />
        <MetricCard label="Bewegungszeit" :value="duration(detail.movingSeconds)" />
        <MetricCard label="Kalorien" :value="detail.calories === null ? '–' : `${detail.calories} kcal`" />
      </dl>

      <section class="detail-panel" aria-labelledby="map-heading">
        <h2 id="map-heading">Laufstrecke</h2>
        <div v-if="routeVisible" ref="mapElement" class="map" aria-label="Karte mit GPS-Track"></div>
        <p v-if="!routeVisible">Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.</p>
      </section>

      <section class="detail-panel" aria-labelledby="charts-heading">
        <h2 id="charts-heading">Trainingsverlauf</h2>
        <p v-if="!charts.pace.length">Für diesen Lauf sind keine Messpunkte vorhanden.</p>
        <div v-else class="charts">
          <div ref="paceElement" class="chart" data-testid="pace-chart" role="img" :aria-label="`Pace-Diagramm (${charts.pace.filter(([, value]) => value !== null).length} Werte)`"></div>
          <div ref="heartElement" class="chart" data-testid="heart-rate-chart" role="img" aria-label="Herzfrequenz-Diagramm"></div>
          <div ref="elevationElement" class="chart" data-testid="elevation-chart" role="img" :aria-label="`Höhenprofil-Diagramm (${charts.elevation.filter(([, value]) => value !== null).length} Werte)`"></div>
          <div v-if="charts.cadence.some((point) => point[1] !== null)" ref="cadenceElement" class="chart" role="img" aria-label="Kadenz-Diagramm"></div>
        </div>
      </section>

      <section class="detail-panel" aria-labelledby="laps-heading">
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
import { DataZoomComponent, GridComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getActivity, type ActivityDetail } from '../api'
import { chartSeries, descendedMeters, displayCadence, formatPace, lapPaceSecondsPerKm, lapRows, paceAxis } from '../activityDetail'
import MetricCard from '../components/MetricCard.vue'

echartsUse([LineChart, DataZoomComponent, GridComponent, TitleComponent, TooltipComponent, CanvasRenderer])

const currentRoute = useRoute()
const detail = ref<ActivityDetail | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const mapElement = ref<HTMLElement | null>(null)
const paceElement = ref<HTMLElement | null>(null)
const heartElement = ref<HTMLElement | null>(null)
const elevationElement = ref<HTMLElement | null>(null)
const cadenceElement = ref<HTMLElement | null>(null)
let map: ReturnType<typeof L.map> | null = null
let chartInstances: ECharts[] = []
let chartResizeObserver: ResizeObserver | null = null
let requestId = 0

const routePoints = computed(() => (detail.value?.records ?? []).filter((record) => record.latitude !== null && record.longitude !== null))
const routeCoords = computed(() => routePoints.value.map((point) => [Number(point.latitude), Number(point.longitude)] as [number, number]))
const routeVisible = computed(() => routeCoords.value.length >= 2)
const routePointsForMap = computed(() => routeVisible.value ? routeCoords.value : [])
const charts = computed(() => chartSeries(detail.value?.records ?? []))
const laps = computed(() => lapRows(detail.value?.laps ?? [], detail.value?.records ?? []))
const paceForRun = computed(() => {
  const activity = detail.value?.activity
  return !activity || activity.elapsedSeconds === null || activity.distanceMeters === null || activity.distanceMeters <= 0 ? '–' : formatPace(activity.elapsedSeconds / (activity.distanceMeters / 1000))
})
const descent = computed(() => {
  if (detail.value?.laps.length && detail.value.laps.every((lap) => lap.descentMeters !== null)) return detail.value.laps.reduce((sum, lap) => sum + lap.descentMeters!, 0)
  return descendedMeters(detail.value?.records ?? [])
})

const distance = (meters: number | null) => meters === null ? '–' : `${(meters / 1000).toFixed(2)} km`
const duration = (seconds: number | null) => {
  if (seconds === null) return '–'
  const minutes = Math.round(seconds / 60)
  return `${Math.floor(minutes / 60) ? `${Math.floor(minutes / 60)} h ` : ''}${String(minutes % 60).padStart(2, '0')} min`
}
const meters = (value: number | null) => value === null ? '–' : `${Math.round(value)} m`
const bpm = (value: number | null) => value === null ? '–' : `${value} bpm`
const date = (value: string) => new Intl.DateTimeFormat(undefined, { dateStyle: 'full', timeStyle: 'short' }).format(new Date(value))

async function loadActivity(id: string) {
  const currentRequest = ++requestId
  disposeVisuals()
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
    if (charts.value.pace.length && paceElement.value && heartElement.value && elevationElement.value) {
      chartInstances = [makeChart(paceElement.value, 'Pace', charts.value.pace, 'min/km', true), makeChart(heartElement.value, 'Herzfrequenz', charts.value.heartRate, 'bpm'), makeChart(elevationElement.value, 'Höhenprofil', charts.value.elevation, 'm')]
      if (charts.value.cadence.some((point) => point[1] !== null) && cadenceElement.value) chartInstances.push(makeChart(cadenceElement.value, 'Kadenz', charts.value.cadence.map(([distance, value]) => [distance, value === null ? null : value * 2]), 'steps/min'))
      connect(chartInstances)
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

function makeChart(element: HTMLElement, name: string, data: [number, number | null][], unit: string, inverse = false) {
  const chart = init(element)
  chart.setOption({
    animation: false,
    title: { text: name, left: 12, top: 8, textStyle: { fontSize: 15 } },
    grid: { left: 58, right: 20, top: 42, bottom: 32 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: (items: Array<{ data: [number, number | null] }>) => {
      const [x, y] = items[0].data
      return `${(x / 1000).toFixed(2)} km<br>${y === null ? '–' : unit === 'min/km' ? formatPace(y) : `${y} ${unit}`}`
    } },
    xAxis: { type: 'value', name: 'Distanz (km)', axisLabel: { formatter: (value: number) => (value / 1000).toFixed(1) } },
    yAxis: { type: 'value', name: unit, inverse, scale: true, axisLabel: { formatter: (value: number) => unit === 'min/km' ? paceAxis(value) : value } },
    dataZoom: [{ type: 'inside', xAxisIndex: 0 }, { type: 'slider', xAxisIndex: 0, height: 16, bottom: 4 }],
    series: [{ type: 'line', name, data, sampling: 'lttb', showSymbol: false, connectNulls: false, smooth: false, lineStyle: { width: 2 } }],
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
.chart { width: 100%; min-width: 0; height: 17rem; }
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
