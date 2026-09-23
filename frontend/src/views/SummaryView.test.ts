import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SummaryView from './SummaryView.vue'

describe('SummaryView', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows a loading state while the API request is pending', () => {
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}) as Promise<Response>)

    expect(mount(SummaryView).get('[role="status"]').text()).toContain('Loading')
  })

  it('renders the typed fixture summary response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      sport: 'running',
      startTime: '2025-01-02T10:15:00Z',
      distanceMeters: 10234.5,
      status: 'decoded',
      recordCount: 42,
      lapCount: 4,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const wrapper = mount(SummaryView)
    await vi.waitFor(() => expect(wrapper.find('[data-testid="fixture-summary"]').exists()).toBe(true))

    expect(fetch).toHaveBeenCalledWith('/api/fixture-summary')
    expect(wrapper.get('[data-testid="sport"]').text()).toBe('running')
    expect(wrapper.get('[data-testid="distance"]').text()).toBe('10.23 km')
    expect(wrapper.get('[data-testid="status"]').text()).toBe('decoded')
    expect(wrapper.get('[data-testid="record-count"]').text()).toBe('42')
    expect(wrapper.get('[data-testid="lap-count"]').text()).toBe('4')
  })

  it('renders an actionable error when the API fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 503 }))

    const wrapper = mount(SummaryView)
    await vi.waitFor(() => expect(wrapper.find('[role="alert"]').exists()).toBe(true))

    expect(wrapper.get('[role="alert"]').text()).toContain('503')
  })
})
