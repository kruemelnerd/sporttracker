import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportView from './ImportView.vue'

const { importFits } = vi.hoisted(() => ({ importFits: vi.fn() }))

vi.mock('../api', () => ({ importFits }))

const file = (name: string) => new File(['fit'], name, { type: 'application/octet-stream' })
async function selectFiles(wrapper: ReturnType<typeof mount>, files: File[]) {
  const picker = wrapper.get('[data-testid="fit-picker"]')
  Object.defineProperty(picker.element, 'files', { value: files })
  await picker.trigger('change')
}
async function settle() { await flushPromises() }

describe('ImportView', () => {
  beforeEach(() => importFits.mockReset())

  it('queues picker and dropped files, then renders their independent outcomes', async () => {
    importFits
      .mockResolvedValueOnce({ outcomes: [{ status: 'IMPORTED', activityId: 1, originalRetained: true, code: null }], batchError: null })
      .mockResolvedValueOnce({ outcomes: [{ status: 'DUPLICATE', activityId: 1, originalRetained: false, code: 'DUPLICATE_HASH' }], batchError: null })

    const wrapper = mount(ImportView)
    const picked = file('picked.fit')
    const dropped = file('dropped.fit')

    await selectFiles(wrapper, [picked])
    await wrapper.get('[data-testid="fit-drop-zone"]').trigger('drop', { dataTransfer: { files: [dropped] } })
    await settle()

    await vi.waitFor(() => expect(wrapper.get('[data-testid="import-row-dropped.fit"]').text()).toContain('DUPLICATE'))

    expect(importFits).toHaveBeenCalledWith([picked])
    expect(importFits).toHaveBeenCalledWith([dropped])
    expect(wrapper.get('[data-testid="import-row-picked.fit"]').text()).toContain('IMPORTED')
    expect(wrapper.get('[data-testid="import-row-dropped.fit"]').text()).toContain('DUPLICATE')
    expect(wrapper.text()).toContain('Imported successfully.')
    expect(wrapper.text()).toContain('Already imported.')
  })

  it.each(['SKIPPED_NON_RUN', 'INVALID', 'FAILED'] as const)('renders the safe %s outcome message', async (status) => {
    importFits.mockResolvedValue({ outcomes: [{ status, activityId: null, originalRetained: false, code: status }], batchError: null })
    const wrapper = mount(ImportView)

    await selectFiles(wrapper, [file(`${status}.fit`)])
    await settle()

    expect(wrapper.text()).toContain(status)
  })

  it('keeps rows and shows typed batch-limit errors', async () => {
    importFits.mockResolvedValue({ outcomes: [], batchError: { code: 'BATCH_COUNT_EXCEEDED', message: 'Select at most 10 files per import.' } })
    const wrapper = mount(ImportView)

    await selectFiles(wrapper, [file('run.fit')])
    await settle()

    expect(wrapper.get('[data-testid="batch-error"]').text()).toContain('Select at most 10 files')
    expect(wrapper.get('[data-testid="import-row-run.fit"]').text()).toContain('PENDING')
  })

  it('retries only the selected failed item without changing its neighbors', async () => {
    importFits
      .mockResolvedValueOnce({ outcomes: [{ status: 'IMPORTED', activityId: 1, originalRetained: true, code: null }, { status: 'FAILED', activityId: null, originalRetained: false, code: 'IMPORT_FAILED' }], batchError: null })
      .mockResolvedValueOnce({ outcomes: [{ status: 'IMPORTED', activityId: 2, originalRetained: true, code: null }], batchError: null })
    const wrapper = mount(ImportView)
    const good = file('good.fit')
    const bad = file('bad.fit')

    await selectFiles(wrapper, [good, bad])
    await settle()
    await vi.waitFor(() => expect(wrapper.find('[data-testid="retry-bad.fit"]').exists()).toBe(true))
    await wrapper.get('[data-testid="retry-bad.fit"]').trigger('click')
    await settle()

    expect(importFits).toHaveBeenNthCalledWith(2, [bad])
    expect(importFits).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="import-row-good.fit"]').text()).toContain('IMPORTED')
    expect(wrapper.get('[data-testid="import-row-bad.fit"]').text()).toContain('IMPORTED')
  })
})
