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

  it('lets the user select multiple FIT files and start one batch', async () => {
    importFits.mockResolvedValue({ outcomes: [
      { status: 'IMPORTED', activityId: 1, originalRetained: true, code: null },
      { status: 'DUPLICATE', activityId: 1, originalRetained: false, code: 'DUPLICATE_HASH' },
    ], batchError: null })

    const wrapper = mount(ImportView)
    const picked = file('picked.fit')
    const duplicate = file('duplicate.fit')

    expect(wrapper.get('[data-testid="start-import"]').attributes('disabled')).toBeDefined()
    await selectFiles(wrapper, [picked, duplicate])
    expect(importFits).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('picked.fit')
    expect(wrapper.text()).toContain('duplicate.fit')
    await wrapper.get('[data-testid="start-import"]').trigger('click')
    await settle()

    expect(importFits).toHaveBeenCalledWith([picked, duplicate])
    expect(wrapper.get('[data-testid="import-row-picked.fit"]').text()).toContain('IMPORTED')
    expect(wrapper.get('[data-testid="import-row-duplicate.fit"]').text()).toContain('DUPLICATE')
    expect(wrapper.text()).toContain('Imported successfully.')
    expect(wrapper.text()).toContain('Already imported.')
  })

  it('rejects files that are not named as FIT files', async () => {
    const wrapper = mount(ImportView)
    await selectFiles(wrapper, [file('notes.txt')])
    expect(wrapper.text()).toContain('Only .fit files can be imported.')
    expect(importFits).not.toHaveBeenCalled()
  })

  it('keeps drag and drop available but waits for explicit import', async () => {
    importFits.mockResolvedValue({ outcomes: [{ status: 'IMPORTED', activityId: 1, originalRetained: true, code: null }], batchError: null })
    const wrapper = mount(ImportView)
    const dropped = file('dropped.fit')
    await wrapper.get('[data-testid="fit-drop-zone"]').trigger('drop', { dataTransfer: { files: [dropped] } })
    expect(importFits).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="start-import"]').trigger('click')
    await flushPromises()
    expect(importFits).toHaveBeenCalledWith([dropped])
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

    await selectFiles(wrapper, Array.from({ length: 11 }, (_, index) => file(`run-${index}.fit`)))
    await wrapper.get('[data-testid="start-import"]').trigger('click')
    await settle()

    expect(wrapper.get('[data-testid="batch-error"]').text()).toContain('Select at most 10 files')
    expect(wrapper.get('[data-testid="import-row-run-0.fit"]').text()).toContain('PENDING')
  })

  it('retries only the selected failed item without changing its neighbors', async () => {
    importFits
      .mockResolvedValueOnce({ outcomes: [{ status: 'IMPORTED', activityId: 1, originalRetained: true, code: null }, { status: 'FAILED', activityId: null, originalRetained: false, code: 'IMPORT_FAILED' }], batchError: null })
      .mockResolvedValueOnce({ outcomes: [{ status: 'IMPORTED', activityId: 2, originalRetained: true, code: null }], batchError: null })
    const wrapper = mount(ImportView)
    const good = file('good.fit')
    const bad = file('bad.fit')

    await selectFiles(wrapper, [good, bad])
    await wrapper.get('[data-testid="start-import"]').trigger('click')
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
