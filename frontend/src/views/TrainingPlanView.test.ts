import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TrainingPlanView from './TrainingPlanView.vue'
import { getPlannedSessions, getTrainingPlan, listTrainingPlans } from '../trainingPlansApi'
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
  afterEach(() => vi.clearAllMocks())

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
})
