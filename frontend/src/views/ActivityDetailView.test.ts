import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ActivityDetailView from './ActivityDetailView.vue'

const { getActivity, chartsMock } = vi.hoisted(() => {
  const instances: Array<Record<string, any>> = []
  const chartsMock = {
    instances,
    connect: vi.fn(),
    use: vi.fn(),
    init: vi.fn((element: HTMLElement) => {
      let clickHandler: ((event: { offsetX: number; offsetY: number }) => void) | undefined
      const instance: Record<string, any> = {
        options: null,
        getDom: () => element,
        setOption: vi.fn((options) => { instance.options = options }),
        dispatchAction: vi.fn(),
        dispose: vi.fn(),
        resize: vi.fn(),
        containPixel: vi.fn(() => true),
        convertFromPixel: vi.fn(() => [75, 150]),
        getZr: () => ({ on: vi.fn((_event: string, handler: typeof clickHandler) => { clickHandler = handler }) }),
        click: (event = { offsetX: 100, offsetY: 100 }) => clickHandler?.(event),
      }
      instances.push(instance)
      return instance
    }),
  }
  return { getActivity: vi.fn(), chartsMock }
})
vi.mock('../api', () => ({ getActivity }))
vi.mock('echarts/core', () => ({
  connect: chartsMock.connect,
  init: chartsMock.init,
  use: chartsMock.use,
}))

const noData = {
  activity: { id: 4, sport: 'running', subSport: null, startTime: '2026-10-12T10:00:00Z', elapsedSeconds: null, distanceMeters: null, averageHeartRate: null, ascentMeters: null },
  movingSeconds: null,
  averageSpeedMetersPerSecond: null,
  maximumHeartRate: null,
  averageCadence: null,
  calories: null,
  garminTrainingMetrics: { aerobicTrainingEffect: null, anaerobicTrainingEffect: null, trainingLoad: null },
  garminActivityMetrics: { activityName: null, averageSpeedMetersPerSecond: null },
  records: [],
  laps: [],
}

async function mountDetail() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/activities/:id', name: 'activity', component: ActivityDetailView },
      { path: '/', component: { template: '<p>Dashboard</p>' } },
    ],
  })
  await router.push('/activities/4')
  await router.isReady()
  const wrapper = mount(ActivityDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function interactiveDetail() {
  const records = [0, 50, 100, 150, 200].map((x) => ({
    elapsedSeconds: x,
    distanceMeters: x,
    latitude: null,
    longitude: null,
    altitudeMeters: 10,
    speedMetersPerSecond: 3,
    heartRate: 140,
    cadence: 80,
    stepLengthMeters: null,
    verticalOscillationCentimeters: null,
    verticalRatioPercent: null,
    groundContactTimeMilliseconds: null,
    groundContactBalancePercent: null,
    temperatureCelsius: null,
    powerWatts: 200,
  }))
  return {
    ...noData,
    activity: { ...noData.activity, elapsedSeconds: 200, distanceMeters: 200 },
    records,
    laps: [
      { lapNumber: 1, durationSeconds: 100, distanceMeters: 50, averageSpeedMetersPerSecond: 3, averageHeartRate: 140, averageCadence: 80, ascentMeters: null, descentMeters: null, workoutStepIndex: 0, repetitionNumber: 1, startElapsedSeconds: 50, endElapsedSeconds: 100, startDistanceMeters: 50, endDistanceMeters: 100 },
      { lapNumber: 2, durationSeconds: 60, distanceMeters: 50, averageSpeedMetersPerSecond: 3, averageHeartRate: 140, averageCadence: 80, ascentMeters: null, descentMeters: null, workoutStepIndex: 1, repetitionNumber: 1, startElapsedSeconds: 100, endElapsedSeconds: 150, startDistanceMeters: 100, endDistanceMeters: 150 },
    ],
    workoutSteps: [
      { stepIndex: 0, name: 'Schnell', durationType: 'time', plannedDurationSeconds: 100, plannedDistanceMeters: null, repeatFromStepIndex: null, repeatCount: null, intensity: 'active', targetType: 'speed', targetZone: null, targetLow: 3.077, targetHigh: 3.175, targetUnit: 'm/s' },
      { stepIndex: 1, name: 'Erholung', durationType: 'time', plannedDurationSeconds: 60, plannedDistanceMeters: null, repeatFromStepIndex: null, repeatCount: null, intensity: 'recovery' },
    ],
  }
}

describe('Activity detail view', () => {
  beforeEach(() => {
    getActivity.mockReset().mockResolvedValue(noData)
    chartsMock.instances.splice(0)
    chartsMock.init.mockClear()
    chartsMock.connect.mockClear()
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      disconnect() {}
    })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('labels metric groups and the table region while preserving optional and empty states', async () => {
    const wrapper = await mountDetail()

    expect(wrapper.find('dl[aria-label="Laufkennzahlen"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.')
    expect(wrapper.text()).toContain('Für diesen Lauf sind keine Messpunkte vorhanden.')
    expect(wrapper.text()).toContain('Für diesen Lauf sind keine Runden oder zuverlässig ableitbaren Kilometer-Splits vorhanden.')
    expect(wrapper.findAll('dl[aria-label="Laufkennzahlen"] dt')).toHaveLength(0)
    expect(wrapper.find('[data-testid="garmin-training-metrics"]').exists()).toBe(false)
  })

  it('labels the horizontally scrollable lap table region', async () => {
    getActivity.mockResolvedValueOnce({ ...noData, laps: [{ lapNumber: 1, distanceMeters: 1000, durationSeconds: 360, averageSpeedMetersPerSecond: null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null }] })
    const wrapper = await mountDetail()

    expect(wrapper.find('[role="region"][aria-label="Rundentabelle; horizontal scrollbar auf kleinen Bildschirmen"]').exists()).toBe(true)
    expect(wrapper.find('table').exists()).toBe(true)
  })

  it('shows linked workout phases and timer pauses separately from ordinary laps', async () => {
    getActivity.mockResolvedValueOnce({
      ...noData,
      laps: [
        { lapNumber: 1, distanceMeters: 400, durationSeconds: 120, averageSpeedMetersPerSecond: null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null, workoutStepIndex: 1, repetitionNumber: 1, startElapsedSeconds: 0, endElapsedSeconds: 120, startDistanceMeters: 0, endDistanceMeters: 400 },
        { lapNumber: 2, distanceMeters: 1000, durationSeconds: 300, averageSpeedMetersPerSecond: null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null, workoutStepIndex: null, repetitionNumber: null, startElapsedSeconds: 120, endElapsedSeconds: 420, startDistanceMeters: 400, endDistanceMeters: 1400 },
      ],
      workoutSteps: [{ stepIndex: 1, name: 'Schnell', durationType: 'distance', plannedDurationSeconds: null, plannedDistanceMeters: 400, repeatFromStepIndex: null, repeatCount: null, intensity: 'active' }],
      pauses: [{ startElapsedSeconds: 130, endElapsedSeconds: 145 }],
    })
    const wrapper = await mountDetail()

    expect(wrapper.find('[data-testid="workout-intervals"]').text()).toContain('Schnell')
    expect(wrapper.find('[data-testid="workout-intervals"] tbody tr').text()).toContain('1')
    expect(wrapper.find('[data-testid="timer-pauses"]').text()).toContain('00:02:10')
    expect(wrapper.find('[data-testid="timer-pauses"]').text()).toContain('00:02:25')
    expect(wrapper.find('[data-testid="laps"]').text()).toContain('2')
    expect(wrapper.find('[data-testid="laps"]').text()).not.toContain('Schnell')
  })

  it('shows only executed workout laps, excludes repeat controls, and supports manual sorting', async () => {
    getActivity.mockResolvedValueOnce({
      ...noData,
      laps: [
        { lapNumber: 3, distanceMeters: 500, durationSeconds: 180, averageSpeedMetersPerSecond: null, averageHeartRate: 140, averageCadence: null, ascentMeters: null, descentMeters: null, workoutStepIndex: 2, repetitionNumber: 1, startElapsedSeconds: 300, endElapsedSeconds: 480, startDistanceMeters: 1500, endDistanceMeters: 2000 },
        { lapNumber: 1, distanceMeters: 1000, durationSeconds: 360, averageSpeedMetersPerSecond: null, averageHeartRate: 130, averageCadence: null, ascentMeters: null, descentMeters: null, workoutStepIndex: 0, repetitionNumber: null, startElapsedSeconds: 0, endElapsedSeconds: 360, startDistanceMeters: 0, endDistanceMeters: 1000 },
        { lapNumber: 2, distanceMeters: 1000, durationSeconds: 240, averageSpeedMetersPerSecond: null, averageHeartRate: 155, averageCadence: null, ascentMeters: null, descentMeters: null, workoutStepIndex: 1, repetitionNumber: 1, startElapsedSeconds: 120, endElapsedSeconds: 360, startDistanceMeters: 500, endDistanceMeters: 1500 },
      ],
      workoutSteps: [
        { stepIndex: 0, name: null, durationType: 'distance', plannedDurationSeconds: null, plannedDistanceMeters: 1000, repeatFromStepIndex: null, repeatCount: null, intensity: 'warmup' },
        { stepIndex: 1, name: null, durationType: 'distance', plannedDurationSeconds: null, plannedDistanceMeters: 1000, repeatFromStepIndex: null, repeatCount: null, intensity: 'active', targetType: 'heart_rate', targetZone: null, targetLow: 150, targetHigh: 165, targetUnit: 'bpm' },
        { stepIndex: 2, name: null, durationType: 'distance', plannedDurationSeconds: null, plannedDistanceMeters: 500, repeatFromStepIndex: null, repeatCount: null, intensity: 'recovery' },
        { stepIndex: 3, name: null, durationType: 'repeat_until_steps_cmplt', plannedDurationSeconds: null, plannedDistanceMeters: null, repeatFromStepIndex: 1, repeatCount: 3, intensity: null },
        { stepIndex: 4, name: null, durationType: 'distance', plannedDurationSeconds: null, plannedDistanceMeters: 1000, repeatFromStepIndex: null, repeatCount: null, intensity: 'cooldown' },
      ],
    })
    const wrapper = await mountDetail()
    const table = wrapper.get('[data-testid="workout-intervals"] table')

    expect(table.text()).toContain('Einlaufen')
    expect(table.text()).toContain('Belastung')
    expect(table.text()).toContain('Erholung')
    expect(table.text()).toContain('150–165 bpm')
    expect(table.text()).not.toContain('repeat_until_steps_cmplt')
    expect(table.findAll('tbody tr').map((row) => row.find('td').text())).toEqual(['1', '2', '3'])

    await wrapper.get('select[aria-label="Workout sortieren nach"]').setValue('duration')
    await wrapper.get('select[aria-label="Sortierrichtung"]').setValue('desc')
    expect(table.findAll('tbody tr').map((row) => row.find('td').text())).toEqual(['1', '2', '3'])
    await wrapper.get('select[aria-label="Sortierrichtung"]').setValue('asc')
    expect(table.findAll('tbody tr').map((row) => row.find('td').text())).toEqual(['3', '2', '1'])
  })

  it('selects a stable workout row after sorting and resets the synchronized zoom', async () => {
    getActivity.mockResolvedValueOnce(interactiveDetail())
    const wrapper = await mountDetail()
    const rows = () => wrapper.findAll('[data-testid="workout-intervals"] tbody tr')

    expect(wrapper.get('[data-testid="workout-intervals"]').text()).toContain('Pace: 5:15–5:25 min/km')
    await wrapper.get('select[aria-label="Workout sortieren nach"]').setValue('duration')
    await wrapper.get('select[aria-label="Sortierrichtung"]').setValue('desc')
    const selected = rows().find((row) => row.text().includes('Schnell'))!
    await selected.trigger('click')

    expect(selected.attributes('aria-selected')).toBe('true')
    expect(selected.classes()).toContain('selected')
    expect(chartsMock.instances.length).toBeGreaterThan(1)
    expect(chartsMock.instances.every((chart) => chart.dispatchAction.mock.calls.some((call: any[]) =>
      call[0].type === 'dataZoom' && call[0].startValue === 47.5 && call[0].endValue === 102.5))).toBe(true)

    await wrapper.get('button[aria-label="Gesamtes Training anzeigen"]').trigger('click')
    expect(rows().every((row) => row.attributes('aria-selected') === 'false')).toBe(true)
    expect(chartsMock.instances.every((chart) => chart.dispatchAction.mock.calls.some((call: any[]) =>
      call[0].type === 'dataZoom' && call[0].startValue === 0 && call[0].endValue === 200))).toBe(true)
  })

  it('adds target bands and selects the interval from a chart x-axis click with an Ist/Ziel tooltip', async () => {
    getActivity.mockResolvedValueOnce(interactiveDetail())
    const wrapper = await mountDetail()
    const pace = chartsMock.instances.find((chart) => chart.getDom().dataset.testid === 'pace-chart')!
    expect(pace.options.series[0].markLine.data).toHaveLength(2)
    expect(pace.options.series[0].markArea.data.some((area: Array<{ xAxis: number; yAxis?: number }>) =>
      area[0].xAxis === 50 && area[0].yAxis !== undefined && area[1].xAxis === 100 && area[1].yAxis !== undefined)).toBe(true)
    const tooltip = pace.options.tooltip.formatter([{ data: [75, 340] }])
    expect(tooltip).toContain('Ist: 5:40 min/km')
    expect(tooltip).toContain('Ziel: 5:15–5:25 min/km')

    pace.click()
    await flushPromises()
    expect(wrapper.findAll('[data-testid="workout-intervals"] tbody tr')[0].attributes('aria-selected')).toBe('true')
    expect(chartsMock.instances.every((chart) => chart.dispatchAction.mock.calls.some((call: any[]) =>
      call[0].type === 'dataZoom' && call[0].startValue === 47.5 && call[0].endValue === 102.5))).toBe(true)
  })

  it('shows only available Garmin training metrics and hides the section when all are absent', async () => {
    let wrapper = await mountDetail()
    expect(wrapper.find('[data-testid="garmin-training-metrics"]').exists()).toBe(false)

    getActivity.mockResolvedValueOnce({ ...noData, garminTrainingMetrics: { aerobicTrainingEffect: 0, anaerobicTrainingEffect: null, trainingLoad: 175.5 } })
    wrapper = await mountDetail()

    expect(wrapper.find('[data-testid="garmin-training-metrics"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Aerober Training Effect')
    expect(wrapper.text()).toContain('0')
    expect(wrapper.text()).toContain('Trainingsbelastung')
    expect(wrapper.text()).toContain('175,5')
    expect(wrapper.text()).not.toContain('Anaerober Training Effect')
  })

  it('updates the preview for format and splits, and copies the complete selected content on click', async () => {
    getActivity.mockResolvedValueOnce({ ...noData, activity: { ...noData.activity, distanceMeters: 5000, elapsedSeconds: 1800 }, laps: [{ lapNumber: 1, distanceMeters: 5000, durationSeconds: 1800, averageSpeedMetersPerSecond: null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null }] })
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = await mountDetail()
    const preview = wrapper.get('[aria-label="Vorschau der Trainingszusammenfassung"]')

    expect((wrapper.get('select[aria-label="Format"]').element as HTMLSelectElement).value).toBe('json')
    expect((preview.element as HTMLTextAreaElement).value).toContain('"distance_km": 5')
    expect((preview.element as HTMLTextAreaElement).value).not.toContain('"splits"')
    await wrapper.get('select[aria-label="Format"]').setValue('yaml')
    await wrapper.get('input[aria-label="Runden/Splits hinzufügen"]').setValue(true)
    expect((preview.element as HTMLTextAreaElement).value).toContain('distance_km: 5')
    expect((preview.element as HTMLTextAreaElement).value).toContain('splits:')

    const selected = (preview.element as HTMLTextAreaElement).value
    await wrapper.get('button[aria-label="Für ChatGPT kopieren"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith(selected)
    expect(wrapper.get('[role="status"]').text()).toBe('Trainingszusammenfassung in die Zwischenablage kopiert.')
  })

  it('reports clipboard failures without claiming the copy succeeded', async () => {
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    const wrapper = await mountDetail()
    await wrapper.get('button[aria-label="Für ChatGPT kopieren"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('Kopieren nicht möglich')
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('shows Garmin activity name, training effect and speed while omitting absent optional metrics', async () => {
    getActivity.mockResolvedValueOnce({
      ...noData,
      activity: { ...noData.activity, distanceMeters: 5000, elapsedSeconds: 1800 },
      garminTrainingMetrics: { aerobicTrainingEffect: 3.7, anaerobicTrainingEffect: 0, trainingLoad: 191.5 },
      garminActivityMetrics: { activityName: 'Morning run', averageSpeedMetersPerSecond: 2.5 },
    })
    const wrapper = await mountDetail()

    expect(wrapper.get('h1').text()).toBe('Morning run')
    expect(wrapper.text()).toContain('Gesamtzeit / verstrichene Zeit')
    expect(wrapper.get('[data-testid="garmin-training-metrics"]').text()).toContain('3,7')
    expect(wrapper.get('[data-testid="garmin-training-metrics"]').text()).toContain('191,5')
    expect(wrapper.text()).toContain('9 km/h')
    expect(wrapper.text()).not.toContain('Max. Geschwindigkeit')
    expect(wrapper.text()).not.toContain('Ruhekalorien')
  })
})
