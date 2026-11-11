import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ActivityDetailView from './ActivityDetailView.vue'

const { getActivity } = vi.hoisted(() => ({ getActivity: vi.fn() }))
vi.mock('../api', () => ({ getActivity }))

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

describe('Activity detail view', () => {
  beforeEach(() => getActivity.mockReset().mockResolvedValue(noData))
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
