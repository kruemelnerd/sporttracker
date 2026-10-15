package local.runningdashboard;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TrainingPlanService {
    private static final Set<String> SPORTS = Set.of("RUN", "BIKE", "STRENGTH", "OTHER");
    private static final Set<String> TYPES = Set.of("EASY", "RECOVERY", "LONG_RUN", "TEMPO", "INTERVAL", "TEST", "RACE", "OTHER");
    private final TrainingPlanRepository repository;

    TrainingPlanService(TrainingPlanRepository repository) {
        this.repository = repository;
    }

    List<PlanSummary> list() {
        return repository.findPlans().stream().map(PlanSummary::from).toList();
    }

    PlanView get(UUID id) {
        var plan = requiredPlan(id);
        return view(plan);
    }

    @Transactional
    PlanView create(PlanRequest request) {
        validatePlan(request);
        var plan = new TrainingPlanModels.Plan(UUID.randomUUID(), request.name().trim(), request.goal().trim(), request.startDate(), request.endDate(),
                request.raceDate(), request.targetTime(), request.description());
        repository.insertPlan(plan);
        return view(plan);
    }

    @Transactional
    PlanView update(UUID id, PlanRequest request) {
        validatePlan(request);
        var current = requiredPlan(id);
        boolean childrenOutOfRange = !repository.findPhases(id).stream().allMatch(phase -> inRange(phase.startDate(), request.startDate(), request.endDate()) && inRange(phase.endDate(), request.startDate(), request.endDate()))
                || repository.findSessions(id, current.startDate(), current.endDate()).stream().anyMatch(session -> !inRange(session.date(), request.startDate(), request.endDate()));
        if (childrenOutOfRange) throw invalid("Planzeitraum darf vorhandene Phasen und Einheiten nicht ausschließen.");
        repository.updatePlan(new TrainingPlanModels.Plan(id, request.name().trim(), request.goal().trim(), request.startDate(), request.endDate(), request.raceDate(), request.targetTime(), request.description()));
        return get(id);
    }

    @Transactional
    void delete(UUID id) {
        requiredPlan(id);
        repository.deletePlan(id);
    }

    @Transactional
    List<TrainingPlanModels.Phase> replacePhases(UUID planId, List<PhaseRequest> requests) {
        var plan = requiredPlan(planId);
        if (requests == null) throw invalid("Phasen fehlen.");
        Set<UUID> existingIds = repository.findPhases(planId).stream().map(TrainingPlanModels.Phase::id).collect(java.util.stream.Collectors.toSet());
        Set<UUID> submittedIds = new HashSet<>();
        var phases = requests.stream().map(request -> {
            validatePhase(request, plan);
            if (request.id() != null && (!existingIds.contains(request.id()) || !submittedIds.add(request.id()))) throw invalid("Phasen-ID ist ungültig oder doppelt.");
            return new TrainingPlanModels.Phase(request.id() == null ? UUID.randomUUID() : request.id(), planId, request.name().trim(), request.startDate(), request.endDate(), request.description());
        }).toList();
        repository.replacePhases(planId, phases);
        return repository.findPhases(planId);
    }

    @Transactional
    TrainingPlanModels.Session createSession(UUID planId, SessionRequest request) {
        var plan = requiredPlan(planId);
        validateSession(request, plan);
        var session = session(UUID.randomUUID(), planId, request);
        repository.insertSession(session);
        return session;
    }

    List<TrainingPlanModels.Session> sessions(UUID planId, LocalDate from, LocalDate to) {
        var plan = requiredPlan(planId);
        if (from == null || to == null || to.isBefore(from)) throw invalid("Der Zeitraum ist ungültig.");
        return repository.findSessions(plan.id(), from, to);
    }

    @Transactional
    TrainingPlanModels.Session updateSession(UUID planId, UUID sessionId, SessionRequest request) {
        var plan = requiredPlan(planId);
        var old = repository.findSession(sessionId).filter(session -> session.trainingPlanId().equals(planId))
                .orElseThrow(() -> notFound("Trainingseinheit wurde nicht gefunden."));
        validateSession(request, plan);
        var updated = session(old.id(), planId, request);
        repository.updateSession(updated);
        return updated;
    }

    @Transactional
    void deleteSession(UUID planId, UUID sessionId) {
        repository.findSession(sessionId).filter(session -> session.trainingPlanId().equals(planId))
                .orElseThrow(() -> notFound("Trainingseinheit wurde nicht gefunden."));
        repository.deleteSession(sessionId);
    }

    private PlanView view(TrainingPlanModels.Plan plan) {
        return new PlanView(plan.id(), plan.name(), plan.goal(), plan.startDate(), plan.endDate(), plan.raceDate(), plan.targetTime(), plan.description(),
                repository.findPhases(plan.id()), repository.findSessions(plan.id(), plan.startDate(), plan.endDate()));
    }

    private TrainingPlanModels.Plan requiredPlan(UUID id) {
        return repository.findPlan(id).orElseThrow(() -> notFound("Trainingsplan wurde nicht gefunden."));
    }

    private static void validatePlan(PlanRequest request) {
        if (request == null || blank(request.name()) || blank(request.goal()) || request.startDate() == null || request.endDate() == null
                || request.endDate().isBefore(request.startDate()) || (request.raceDate() != null && !inRange(request.raceDate(), request.startDate(), request.endDate()))
                || (request.targetTime() != null && request.targetTime() <= 0)) throw invalid("Trainingsplan: Name, Ziel und gültiger Zeitraum sind erforderlich; Wettkampfdatum und Zielzeit müssen zum Zeitraum passen.");
    }

    private static void validatePhase(PhaseRequest phase, TrainingPlanModels.Plan plan) {
        if (phase == null || blank(phase.name()) || phase.startDate() == null || phase.endDate() == null
                || phase.endDate().isBefore(phase.startDate()) || !inRange(phase.startDate(), plan.startDate(), plan.endDate())
                || !inRange(phase.endDate(), plan.startDate(), plan.endDate())) throw invalid("Trainingsphase benötigt Name und gültigen Zeitraum innerhalb des Plans.");
    }

    private static void validateSession(SessionRequest session, TrainingPlanModels.Plan plan) {
        if (session == null || session.date() == null || !inRange(session.date(), plan.startDate(), plan.endDate())
                || !SPORTS.contains(session.sport()) || !TYPES.contains(session.sessionType()) || blank(session.title())
                || !positive(session.targetDistanceKm()) || !positive(session.targetPaceFrom()) || !positive(session.targetPaceTo())
                || (session.targetDurationMinutes() != null && session.targetDurationMinutes() <= 0)
                || (session.targetRpe() != null && (session.targetRpe() < 1 || session.targetRpe() > 10))
                || (session.targetPaceFrom() != null && session.targetPaceTo() != null && session.targetPaceFrom() > session.targetPaceTo())) {
            throw invalid("Trainingseinheit enthält ungültige Angaben (Datum, Sport, Typ, Distanz, Dauer, Pace oder RPE).");
        }
    }

    private static TrainingPlanModels.Session session(UUID id, UUID planId, SessionRequest request) {
        return new TrainingPlanModels.Session(id, planId, request.date(), request.sport(), request.sessionType(), request.title().trim(), request.targetDistanceKm(), request.targetDurationMinutes(), request.targetPaceFrom(), request.targetPaceTo(), request.targetRpe(), request.instructions(), request.notes());
    }

    private static boolean positive(Double value) { return value == null || (Double.isFinite(value) && value > 0); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean inRange(LocalDate date, LocalDate start, LocalDate end) { return !date.isBefore(start) && !date.isAfter(end); }
    private static TrainingPlanException invalid(String message) { return new TrainingPlanException(HttpStatus.BAD_REQUEST, "INVALID_TRAINING_PLAN", message); }
    private static TrainingPlanException notFound(String message) { return new TrainingPlanException(HttpStatus.NOT_FOUND, "TRAINING_PLAN_NOT_FOUND", message); }

    record PlanRequest(String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, Integer targetTime, String description) { }
    record PhaseRequest(UUID id, String name, LocalDate startDate, LocalDate endDate, String description) { }
    record SessionRequest(LocalDate date, String sport, String sessionType, String title, Double targetDistanceKm, Integer targetDurationMinutes,
                          Double targetPaceFrom, Double targetPaceTo, Integer targetRpe, String instructions, String notes) { }
    record PlanSummary(UUID id, String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, Integer targetTime, String description) {
        static PlanSummary from(TrainingPlanModels.Plan plan) { return new PlanSummary(plan.id(), plan.name(), plan.goal(), plan.startDate(), plan.endDate(), plan.raceDate(), plan.targetTime(), plan.description()); }
    }
    record PlanView(UUID id, String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, Integer targetTime, String description,
                    List<TrainingPlanModels.Phase> phases, List<TrainingPlanModels.Session> sessions) { }
}
