<template>
  <section class="detail">
    <RouterLink to="/">← Zurück zum Dashboard</RouterLink>
    <p v-if="loading" role="status">Laufdetails werden geladen …</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-if="detail && !loading && !error">
      <header>
        <h2>{{ date(detail.activity.startTime) }}</h2>
        <p>{{ distance(detail.activity.distanceMeters) }} · {{ duration(detail.activity.elapsedSeconds) }} · {{ paceForRun }}</p>
      </header>
      <dl class="metrics">
        <div><dt>Ø Herzfrequenz</dt><dd>{{ bpm(detail.activity.averageHeartRate) }}</dd></div>
        <div><dt>Max. Herzfrequenz</dt><dd>{{ bpm(detail.maximumHeartRate) }}</dd></div>
        <div><dt>Höhenmeter bergauf</dt><dd>{{ meters(detail.activity.ascentMeters) }}</dd></div>
        <div><dt>Höhenmeter bergab</dt><dd>{{ meters(descent) }}</dd></div>
        <div><dt>Ø Kadenz</dt><dd>{{ displayCadence(detail.averageCadence) }}</dd></div>
        <div><dt>Bewegungszeit</dt><dd>{{ duration(detail.movingSeconds) }}</dd></div>
        <div><dt>Kalorien</dt><dd>{{ detail.calories === null ? '–' : `${detail.calories} kcal` }}</dd></div>
      </dl>

      <section aria-labelledby="map-heading">
        <h3 id="map-heading">Laufstrecke</h3>
        <div v-if="routeVisible" ref="mapElement" class="map" aria-label="Karte mit GPS-Track"></div>
        <p v-if="!routeVisible">Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.</p>
      </section>

      <section aria-labelledby="charts-heading">
        <h3 id="charts-heading">Trainingsverlauf</h3>
        <p v-if="!charts.pace.length">Für diesen Lauf sind keine Messpunkte vorhanden.</p>
        <div v-else class="charts">
          <div ref="paceElement" class="chart" data-testid="pace-chart" :aria-label="`Pace-Diagramm (${charts.pace.filter(([, value]) => value !== null).length} Werte)`"></div>
          <div ref="heartElement" class="chart" data-testid="heart-rate-chart" aria-label="Herzfrequenz-Diagramm"></div>
          <div ref="elevationElement" class="chart" data-testid="elevation-chart" :aria-label="`Höhenprofil-Diagramm (${charts.elevation.filter(([, value]) => value !== null).length} Werte)`"></div>
          <div v-if="charts.cadence.some((point) => point[1] !== null)" ref="cadenceElement" class="chart" aria-label="Kadenz-Diagramm"></div>
        </div>
      </section>

      <section aria-labelledby="laps-heading">
        <h3 id="laps-heading">Runden</h3>
        <p v-if="!laps.length">Für diesen Lauf sind keine Runden oder zuverlässig ableitbaren Kilometer-Splits vorhanden.</p>
        <div v-else class="table-wrap">
          <table>
            <thead><tr><th>Runde</th><th>Distanz</th><th>Dauer</th><th>Ø Pace</th><th>Ø HF</th><th>Höhenmeter +/−</th><th>Ø Kadenz</th></tr></thead>
            <tbody><tr v-for="lap in laps" :key="lap.lapNumber">
              <td>{{ lap.lapNumber }} <small v-if="lap.source === 'derived'">(abgeleitet)</small></td>
              <td>{{ distance(lap.distanceMeters) }}</td><td>{{ duration(lap.durationSeconds) }}</td>
              <td>{{ formatPace(lap.averageSpeedMetersPerSecond && lap.averageSpeedMetersPerSecond > 0 ? 1000 / lap.averageSpeedMetersPerSecond : null) }}</td>
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
import { chartSeries, descendedMeters, displayCadence, formatPace, lapRows, paceAxis } from '../activityDetail'

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
.detail { max-width: 72rem; margin: 0 auto; padding: 1rem; }
.metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .75rem; }
.metrics div { border: 1px solid #d8dde3; border-radius: .5rem; padding: .75rem; }
.metrics dt { color: #53606d; font-size: .9rem; }
.metrics dd { font-size: 1.2rem; font-weight: 650; margin: .25rem 0 0; }
.map { height: 24rem; width: 100%; border-radius: .5rem; }
.charts { display: grid; grid-template-columns: 1fr; gap: .5rem; }
.chart { width: 100%; height: 17rem; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { padding: .6rem; text-align: left; border-bottom: 1px solid #d8dde3; white-space: nowrap; }
@media (max-width: 600px) { .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .map { height: 18rem; } .chart { height: 15rem; } }
</style>
