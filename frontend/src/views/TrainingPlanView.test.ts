import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TrainingPlanView from './TrainingPlanView.vue'
import { createPlannedSession, createTrainingPlan, getPlannedSessions, getTrainingPlan, listTrainingPlans, replaceTrainingPhases, updatePlannedSession, type PlannedSession } from '../trainingPlansApi'
import { isoWeekLabel, mondayOfLocalDate, shiftWeek } from '../trainingPlan'

vi.mock('../trainingPlansApi', () => ({
  listTrainingPlans: vi.fn(),
  getTrainingPlan: vi.fn(),
  getPlannedSessions: vi.fn(),
  createTrainingPlan: vi.fn(),
  updateTrainingPlan: vi.fn(),
  deleteTrainingPlan: vi.fn(),
  replaceTrainingPhases: vi.fn(),
  createPlannedSession: vi.fn(),
  updatePlannedSession: vi.fn(),
  deletePlannedSession: vi.fn(),
}))

const today = new Date()
const todayString = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
const monday = mondayOfLocalDate(todayString)
const plan = {
  id: 'plan-1', name: 'Road to Den Haag', goal: 'Halbmarathon', startDate: shiftWeek(monday, -3), endDate: shiftWeek(monday, 3),
  raceDate: null, targetTime: null, description: null,
}

describe('TrainingPlanView weekly overview', () => {
  beforeEach(() => {
    vi.mocked(listTrainingPlans).mockResolvedValue([plan])
    vi.mocked(getTrainingPlan).mockResolvedValue({ ...plan, phases: [{ id: 'phase-1', trainingPlanId: plan.id, name: 'Grundlage', startDate: plan.startDate, endDate: plan.endDate, description: null }], sessions: [] })
    vi.mocked(getPlannedSessions).mockImplementation(async (_id, from) => from === monday ? [
      { id: 'run-1', trainingPlanId: plan.id, date: monday, sport: 'RUN', sessionType: 'EASY', title: 'Locker', targetDistanceKm: 8, targetDurationMinutes: null, targetPaceFrom: null, targetPaceTo: null, targetRpe: 3, instructions: null, notes: null },
      { id: 'bike-1', trainingPlanId: plan.id, date: shiftWeek(monday, 0), sport: 'BIKE', sessionType: 'OTHER', title: 'Pendeln', targetDistanceKm: 60, targetDurationMinutes: 150, targetPaceFrom: null, targetPaceTo: null, targetRpe: null, instructions: null, notes: null },
    ] : [])
  })
  afterEach(() => vi.resetAllMocks())

  it('shows the current ISO week and sums RUN distance without BIKE distance', async () => {
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('8 km'))

    expect(wrapper.text()).toContain(isoWeekLabel(monday))
    expect(wrapper.get('[data-testid="run-count"]').text()).toBe('1')
    expect(wrapper.get('[data-testid="run-distance"]').text()).toBe('8 km')
    expect(wrapper.get('[data-testid="other-sessions"]').text()).toBe('1')
    expect(wrapper.findAll('[data-testid^="week-day-"]')).toHaveLength(7)
  })

  it('navigates to a visible empty week with zero metrics', async () => {
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.find('[data-testid="run-count"]').exists()).toBe(true))
    await wrapper.get('[aria-label="Nächste Woche"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.text()).toContain(isoWeekLabel(shiftWeek(monday, 1))))

    expect(wrapper.text()).toContain('Keine geplanten Einheiten')
    expect(wrapper.get('[data-testid="run-count"]').text()).toBe('0')
    expect(wrapper.get('[data-testid="run-distance"]').text()).toBe('0 km')
  })

  it('creates a plan from the accessible plan form', async () => {
    vi.mocked(createTrainingPlan).mockResolvedValue({ ...plan, phases: [], sessions: [] })
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.text()).toContain(plan.name))
    await wrapper.get('button').trigger('click')
    await wrapper.get('input[name="planName"]').setValue('New plan')
    await wrapper.get('input[name="goal"]').setValue('10 km')
    await wrapper.get('input[name="startDate"]').setValue(plan.startDate)
    await wrapper.get('input[name="endDate"]').setValue(plan.endDate)
    await wrapper.get('form[aria-label="Trainingsplan erstellen"]').trigger('submit')

    expect(createTrainingPlan).toHaveBeenCalledWith(expect.objectContaining({ name: 'New plan', goal: '10 km' }))
  })

  it('moves an existing session by editing its calendar date', async () => {
    const session: PlannedSession = {
      id: 'run-1', trainingPlanId: plan.id, date: monday, sport: 'RUN', sessionType: 'EASY', title: 'Locker', targetDistanceKm: 8,
      targetDurationMinutes: null, targetPaceFrom: null, targetPaceTo: null, targetRpe: 3, instructions: null, notes: null,
    }
    vi.mocked(getPlannedSessions).mockResolvedValue([session])
    vi.mocked(getTrainingPlan).mockResolvedValue({ ...plan, phases: [], sessions: [] })
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('Locker'))
    await wrapper.get('[aria-label="Training bearbeiten: Locker"]').trigger('click')
    const movedDateObject = new Date(`${monday}T12:00:00`)
    movedDateObject.setDate(movedDateObject.getDate() + 1)
    const movedDate = `${movedDateObject.getFullYear()}-${String(movedDateObject.getMonth() + 1).padStart(2, '0')}-${String(movedDateObject.getDate()).padStart(2, '0')}`
    await wrapper.get('input[name="sessionDate"]').setValue(movedDate)
    await wrapper.get('input[name="sessionTitle"]').setValue('Verschoben')
    await wrapper.get('form[aria-label="Training bearbeiten"]').trigger('submit')

    expect(updatePlannedSession).toHaveBeenCalledWith(plan.id, session.id, expect.objectContaining({ title: 'Verschoben', date: movedDate }))
  })

  it('creates an optional-target session through the backend API', async () => {
    vi.mocked(getPlannedSessions).mockResolvedValue([])
    vi.mocked(createPlannedSession).mockResolvedValue({
      id: 'new-session', trainingPlanId: plan.id, date: monday, sport: 'RUN', sessionType: 'EASY', title: 'Locker',
      targetDistanceKm: null, targetDurationMinutes: null, targetPaceFrom: null, targetPaceTo: null, targetRpe: null, instructions: null, notes: null,
    })
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.text()).toContain(plan.name))
    await wrapper.get('button[aria-label="Training hinzufügen"]').trigger('click')
    await wrapper.get('input[name="sessionTitle"]').setValue('Locker')
    await wrapper.get('form[aria-label="Training hinzufügen"]').trigger('submit')

    expect(createPlannedSession).toHaveBeenCalledWith(plan.id, expect.objectContaining({ date: monday, title: 'Locker', targetDistanceKm: null }))
  })

  it('keeps a phase ID when editing its name', async () => {
    vi.mocked(replaceTrainingPhases).mockResolvedValue([{ id: 'phase-1', trainingPlanId: plan.id, name: 'Aufbau', startDate: plan.startDate, endDate: plan.endDate, description: null }])
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.text()).toContain('Grundlage'))
    await wrapper.get('[aria-label="Phase bearbeiten: Grundlage"]').trigger('click')
    await wrapper.get('#phase-name').setValue('Aufbau')
    await wrapper.get('form[aria-label="Trainingsphase bearbeiten"]').trigger('submit')

    expect(replaceTrainingPhases).toHaveBeenCalledWith(plan.id, [expect.objectContaining({ id: 'phase-1', name: 'Aufbau' })])
  })

  it('shows backend validation errors when creating a session fails', async () => {
    vi.mocked(getPlannedSessions).mockResolvedValue([])
    vi.mocked(createPlannedSession).mockRejectedValue(new Error('RPE muss zwischen 1 und 10 liegen.'))
    const wrapper = mount(TrainingPlanView)
    await vi.waitFor(() => expect(wrapper.text()).toContain(plan.name))
    await wrapper.get('button[aria-label="Training hinzufügen"]').trigger('click')
    await wrapper.get('input[name="sessionTitle"]').setValue('Zu hart')
    await wrapper.get('input[name="targetRpe"]').setValue('11')
    await wrapper.get('form[aria-label="Training hinzufügen"]').trigger('submit')

    await vi.waitFor(() => expect(wrapper.get('[role="alert"]').text()).toContain('RPE muss zwischen 1 und 10 liegen.'))
  })
})
