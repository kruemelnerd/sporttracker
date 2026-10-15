import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import App from './App.vue'

const mountApp = async (path = '/') => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: { template: '<p>Dashboard content</p>' } },
      { path: '/import', name: 'import', component: { template: '<p>Import content</p>' } },
      { path: '/training-plan', name: 'training-plan', component: { template: '<p>Training plan content</p>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('App shell', () => {
  it('provides main landmark, skip target, product navigation, and active route semantics', async () => {
    const wrapper = await mountApp()

    expect(wrapper.find('main#main-content').exists()).toBe(true)
    expect(wrapper.find('a[href="#main-content"]').exists()).toBe(true)
    expect(wrapper.find('nav a[href="/"]').text()).toContain('Übersicht')
    expect(wrapper.find('nav a[href="/import"]').text()).toContain('Import')
    expect(wrapper.find('nav a[aria-current="page"]').attributes('href')).toBe('/')
  })

  it('provides a current Trainingsplan navigation link', async () => {
    const wrapper = await mountApp('/training-plan')

    expect(wrapper.find('nav a[href="/training-plan"]').text()).toContain('Trainingsplan')
    expect(wrapper.find('nav a[aria-current="page"]').attributes('href')).toBe('/training-plan')
  })
})
