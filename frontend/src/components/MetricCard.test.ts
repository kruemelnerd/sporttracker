import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MetricCard from './MetricCard.vue'

describe('MetricCard', () => {
  it('exposes the metric label and value as a description list', () => {
    const wrapper = mount(MetricCard, { props: { label: 'Distanz', value: '12,4 km' } })

    expect(wrapper.element.tagName).toBe('DIV')
    expect(wrapper.find('dt').text()).toBe('Distanz')
    expect(wrapper.find('dd').text()).toBe('12,4 km')
  })
})
