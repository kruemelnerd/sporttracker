import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ActivityTypeIcon from './ActivityTypeIcon.vue'

describe('ActivityTypeIcon', () => {
  it.each([null, 'trail_run'])('renders a decorative local SVG for subSport %s', (subSport) => {
    const wrapper = mount(ActivityTypeIcon, { props: { subSport } })

    expect(wrapper.find('svg[aria-hidden="true"]').exists()).toBe(true)
    expect(wrapper.find('svg title').exists()).toBe(false)
  })
})
