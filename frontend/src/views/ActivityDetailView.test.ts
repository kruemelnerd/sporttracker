import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

  it('labels metric groups and the table region while preserving optional and empty states', async () => {
    const wrapper = await mountDetail()

    expect(wrapper.find('dl[aria-label="Laufkennzahlen"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Für diesen Lauf sind keine GPS-Trackpunkte vorhanden.')
    expect(wrapper.text()).toContain('Für diesen Lauf sind keine Messpunkte vorhanden.')
    expect(wrapper.text()).toContain('Für diesen Lauf sind keine Runden oder zuverlässig ableitbaren Kilometer-Splits vorhanden.')
    expect(wrapper.find('dt').element.nextElementSibling?.textContent).toBe('–')
  })

  it('labels the horizontally scrollable lap table region', async () => {
    getActivity.mockResolvedValueOnce({ ...noData, laps: [{ lapNumber: 1, distanceMeters: 1000, durationSeconds: 360, averageSpeedMetersPerSecond: null, averageHeartRate: null, averageCadence: null, ascentMeters: null, descentMeters: null }] })
    const wrapper = await mountDetail()

    expect(wrapper.find('[role="region"][aria-label="Rundentabelle; horizontal scrollbar auf kleinen Bildschirmen"]').exists()).toBe(true)
    expect(wrapper.find('table').exists()).toBe(true)
  })
})
