import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportView from './ImportView.vue'

const { importFits, getIntervalsIcuStatus, startIntervalsIcuSync } = vi.hoisted(() => ({
  importFits: vi.fn(), getIntervalsIcuStatus: vi.fn(), startIntervalsIcuSync: vi.fn(),
}))

vi.mock('../api', () => ({ importFits, getIntervalsIcuStatus, startIntervalsIcuSync }))

const file = (name: string) => new File(['fit'], name, { type: 'application/octet-stream' })
async function selectFiles(wrapper: ReturnType<typeof mount>, files: File[]) {
  const picker = wrapper.get('[data-testid="fit-picker"]')
  Object.defineProperty(picker.element, 'files', { value: files })
  await picker.trigger('change')
}
async function settle() { await flushPromises() }
const status = (overrides = {}) => ({
  configured: true,
  running: false,
  lastSuccessfulSyncAt: null,
  lastAttemptAt: null,
  retryAfterUntil: null,
  lastResult: null,
  message: null,
  ...overrides,
})

describe('ImportView', () => {
  beforeEach(() => {
    importFits.mockReset()
    getIntervalsIcuStatus.mockReset().mockResolvedValue(status())
    startIntervalsIcuSync.mockReset().mockResolvedValue(status())
  })

  it('shows the unconfigured Intervals state and setup hint', async () => {
    getIntervalsIcuStatus.mockResolvedValue(status({ configured: false }))
    const wrapper = mount(ImportView)
    await settle()

    expect(wrapper.get('[data-testid="intervals-status"]').text()).toContain('Nicht konfiguriert')
    expect(wrapper.text()).toContain('INTERVALS_ICU_API_KEY')
    expect(wrapper.get('[data-testid="intervals-sync"]').attributes('disabled')).toBeDefined()
  })

  it('shows connected state and the last successful sync time', async () => {
    getIntervalsIcuStatus.mockResolvedValue(status({ lastSuccessfulSyncAt: '2026-10-19T14:43:00Z' }))
    const wrapper = mount(ImportView)
    await settle()

    expect(wrapper.get('[data-testid="intervals-status"]').text()).toContain('Verbunden')
    expect(wrapper.text()).toContain('19.10.2026')
  })

  it('shows sync totals and makes a manual sync request', async () => {
    const completed = status({ lastResult: { started: true, checked: 3, imported: 1, alreadyPresent: 2, skipped: 0, failed: 0, error: null, activities: [] } })
    startIntervalsIcuSync.mockResolvedValue(completed)
    const wrapper = mount(ImportView)
    await settle()

    await wrapper.get('[data-testid="intervals-sync"]').trigger('click')
    await settle()

    expect(startIntervalsIcuSync).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="intervals-result"]').text()).toContain('3 Aktivitäten geprüft')
    expect(wrapper.get('[data-testid="intervals-result"]').text()).toContain('1 neuer Lauf importiert')
    expect(wrapper.get('[data-testid="intervals-result"]').text()).toContain('2 bereits vorhanden')
  })

  it('disables the button while a sync is running', async () => {
    getIntervalsIcuStatus.mockResolvedValue(status({ running: true }))
    const wrapper = mount(ImportView)
    await settle()

    expect(wrapper.text()).toContain('Synchronisierung läuft')
    expect(wrapper.get('[data-testid="intervals-sync"]').attributes('disabled')).toBeDefined()
  })

  it('shows the last sync error', async () => {
    getIntervalsIcuStatus.mockResolvedValue(status({ lastResult: {
      started: true, checked: 1, imported: 0, alreadyPresent: 0, skipped: 0, failed: 1,
      error: 'Intervals.icu denied access (403).', activities: [],
    } }))
    const wrapper = mount(ImportView)
    await settle()

    expect(wrapper.get('[data-testid="intervals-error"]').text()).toContain('Intervals.icu denied access (403).')
  })

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
    expect(wrapper.get('[data-testid="import-row-picked.fit"]').text()).toContain('Import starten, um die ausgewählten Dateien zu verarbeiten.')
    await wrapper.get('[data-testid="start-import"]').trigger('click')
    await settle()

    expect(importFits).toHaveBeenCalledWith([picked, duplicate])
    expect(wrapper.get('[data-testid="import-row-picked.fit"]').text()).toContain('IMPORTED')
    expect(wrapper.get('[data-testid="import-row-duplicate.fit"]').text()).toContain('DUPLICATE')
    expect(wrapper.text()).toContain('Erfolgreich importiert')
    expect(wrapper.text()).toContain('Bereits importiert')
  })

  it('rejects files that are not named as FIT files', async () => {
    const wrapper = mount(ImportView)
    await selectFiles(wrapper, [file('notes.txt')])
    expect(wrapper.text()).toContain('Nur .fit-Dateien können importiert werden.')
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
    await wrapper.get('[data-testid="start-import"]').trigger('click')
    await settle()

    expect(wrapper.text()).toContain(status)
    expect(wrapper.get(`[data-testid="import-row-${status}.fit"]`).text()).toMatch(/Kein Lauftraining|Ungültige FIT-Datei|Import fehlgeschlagen/)
    expect(wrapper.find(`[data-testid="import-row-${status}.fit"] [role="${status === 'SKIPPED_NON_RUN' ? 'status' : 'alert'}"]`).exists()).toBe(true)
  })

  it('keeps rows and shows typed batch-limit errors', async () => {
    importFits.mockResolvedValue({ outcomes: [], batchError: { code: 'BATCH_COUNT_EXCEEDED', message: 'Select at most 10 files per import.' } })
    const wrapper = mount(ImportView)

    await selectFiles(wrapper, Array.from({ length: 11 }, (_, index) => file(`run-${index}.fit`)))
    await wrapper.get('[data-testid="start-import"]').trigger('click')
    await settle()

    expect(wrapper.get('[data-testid="batch-error"]').attributes('role')).toBe('alert')
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

  it('provides labelled keyboard activation and a live selected-file count', async () => {
    const wrapper = mount(ImportView)
    const picker = wrapper.get('[data-testid="fit-picker"]')
    expect(picker.attributes('aria-label')).toBe('FIT-Dateien auswählen')
    const click = vi.spyOn(picker.element as HTMLInputElement, 'click')
    await wrapper.get('[data-testid="fit-drop-zone"]').trigger('click')
    expect(click).toHaveBeenCalledOnce()

    await selectFiles(wrapper, [file('one.fit'), file('two.fit')])
    expect(wrapper.get('[data-testid="selection-count"]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-testid="selection-count"]').text()).toContain('2 Dateien ausgewählt')
  })
})
