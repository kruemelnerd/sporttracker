import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ImportOutcomeBadge from './ImportOutcomeBadge.vue'

describe('ImportOutcomeBadge', () => {
  it.each([
    ['IMPORTED', 'Importiert', 'success'],
    ['DUPLICATE', 'Bereits importiert', 'info'],
    ['SKIPPED_NON_RUN', 'Kein Lauftraining', 'warning'],
    ['INVALID', 'Ungültige FIT-Datei', 'danger'],
    ['FAILED', 'Import fehlgeschlagen', 'danger'],
  ] as const)('presents %s with text and semantic styling', (status, text, tone) => {
    const wrapper = mount(ImportOutcomeBadge, { props: { status } })

    expect(wrapper.text()).toContain(text)
    expect(wrapper.classes()).toContain(`outcome-${tone}`)
    expect(wrapper.element.tagName).toBe('SPAN')
  })
})
