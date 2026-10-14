import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'

describe('DashboardView', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders calculated totals, weekly buckets and a link to the run', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify([
      { id: 12, sport: 'running', subSport: 'trail', startTime: new Date().toISOString(), elapsedSeconds: 3600, distanceMeters: 10000, averageHeartRate: 150, ascentMeters: 200 },
    ]), { status: 200 }))

    const wrapper = mount(DashboardView, { global: { stubs: { RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] } } } })
    await vi.waitFor(() => expect(wrapper.text()).toContain('10.00 km'))

    expect(wrapper.get('[data-testid="run-count"]').text()).toBe('1')
    expect(wrapper.get('[data-testid="total-time"]').text()).toBe('1 h 00 min')
    expect(wrapper.get('[data-testid="average-distance"]').text()).toBe('10.00 km')
    expect(wrapper.get('[data-testid="average-pace"]').text()).toBe('6:00 min/km')
    expect(wrapper.get('[data-testid="total-ascent"]').text()).toBe('200 m')
    expect(wrapper.get('[data-testid="activity-12"]').attributes('href')).toBe('/activities/12')
    expect(wrapper.findAll('.metric-card')).toHaveLength(6)
    expect(wrapper.findAll('[data-testid^="week-bar-"]')).toHaveLength(6)
    expect(wrapper.get('[data-testid="week-bar-5"]').text()).toContain('10.00 km')
    expect(wrapper.get('[data-testid="activity-12"]').find('svg[aria-hidden="true"]').exists()).toBe(true)
  })

  it('shows a useful empty state without null-like values', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
    const wrapper = mount(DashboardView, { global: { stubs: { RouterLink: true } } })
    await vi.waitFor(() => expect(wrapper.find('[data-testid="empty-state"]').exists()).toBe(true))
    expect(wrapper.text()).not.toMatch(/NaN|undefined|null/)
  })

  it('provides a visible link from the dashboard to FIT import', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', { status: 200 }))
    const wrapper = mount(DashboardView, { global: { stubs: { RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] } } } })
    await vi.waitFor(() => expect(wrapper.find('[href="/import"]').exists()).toBe(true))
    expect(wrapper.get('[href="/import"]').text()).toContain('FIT-Dateien importieren')
  })
})
