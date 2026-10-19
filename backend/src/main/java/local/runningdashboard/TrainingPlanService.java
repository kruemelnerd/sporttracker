package local.runningdashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
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
                request.raceDate(), integer(request.targetTime()), request.description());
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
        repository.updatePlan(new TrainingPlanModels.Plan(id, request.name().trim(), request.goal().trim(), request.startDate(), request.endDate(), request.raceDate(), integer(request.targetTime()), request.description()));
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
        try {
            repository.insertSession(session);
        } catch (DuplicateKeyException duplicate) {
            throw new TrainingPlanException(HttpStatus.CONFLICT, "DUPLICATE_PLANNED_SESSION", "Diese Trainingseinheit ist bereits geplant.");
        }
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

    PlanDocument exportPlan(UUID id) {
        var plan = requiredPlan(id);
        var phases = repository.findPhases(id).stream()
                .map(phase -> new ImportPhase(phase.name(), phase.startDate(), phase.endDate(), phase.description())).toList();
        var sessions = repository.findSessions(id, plan.startDate(), plan.endDate()).stream()
                .map(session -> new ImportSession(session.date(), session.sport(), session.sessionType(), session.title(), session.targetDistanceKm(),
                        decimal(session.targetDurationMinutes()), session.targetPaceFrom(), session.targetPaceTo(), decimal(session.targetRpe()), session.instructions(), session.notes())).toList();
        return new PlanDocument(1, new ImportPlan(plan.name(), plan.goal(), plan.startDate(), plan.endDate(), plan.raceDate(), decimal(plan.targetTime()), plan.description(), phases, sessions));
    }

    @Transactional
    ImportResult importPlan(ImportDocument document) {
        if (document == null || !Integer.valueOf(1).equals(document.schemaVersion()) || document.plan() == null) {
            throw invalid("JSON-Format oder Schema-Version wird nicht unterstützt (erwartet wird Version 1).");
        }
        var input = document.plan();
        var planRequest = new PlanRequest(input.name(), input.goal(), input.startDate(), input.endDate(), input.raceDate(), input.targetTime(), input.description());
        validatePlan(planRequest);
        if (input.phases() == null || input.sessions() == null) throw invalid("Der Plan muss Phasen und Trainingseinheiten als Listen enthalten.");

        var phases = input.phases().stream().map(phase -> {
            if (phase == null) throw invalid("Trainingsphase darf nicht leer sein.");
            var request = new PhaseRequest(null, phase.name(), phase.startDate(), phase.endDate(), phase.description());
            validatePhase(request, new TrainingPlanModels.Plan(null, planRequest.name(), planRequest.goal(), planRequest.startDate(), planRequest.endDate(), planRequest.raceDate(), integer(planRequest.targetTime()), planRequest.description()));
            return new TrainingPlanModels.Phase(null, null, phase.name().trim(), phase.startDate(), phase.endDate(), phase.description());
        }).toList();
        var sessions = input.sessions().stream().map(session -> {
            if (session == null) throw invalid("Trainingseinheit darf nicht leer sein.");
            var request = new SessionRequest(session.date(), session.sport(), session.sessionType(), session.title(), session.targetDistanceKm(), session.targetDurationMinutes(),
                    session.targetPaceFrom(), session.targetPaceTo(), session.targetRpe(), session.instructions(), session.notes());
            validateSession(request, new TrainingPlanModels.Plan(null, planRequest.name(), planRequest.goal(), planRequest.startDate(), planRequest.endDate(), planRequest.raceDate(), integer(planRequest.targetTime()), planRequest.description()));
            return request;
        }).toList();

        var candidate = new TrainingPlanModels.Plan(null, planRequest.name().trim(), planRequest.goal().trim(), planRequest.startDate(), planRequest.endDate(),
                planRequest.raceDate(), integer(planRequest.targetTime()), planRequest.description());
        var duplicate = repository.findDuplicate(candidate);
        if (duplicate.isPresent() && !Boolean.TRUE.equals(document.replaceExisting())) {
            throw new TrainingPlanException(HttpStatus.CONFLICT, "DUPLICATE_TRAINING_PLAN", "Ein Trainingsplan mit diesem Namen, Ziel und Zeitraum ist bereits vorhanden. Ersetzen muss ausdrücklich bestätigt werden.");
        }

        boolean replaced = duplicate.isPresent();
        UUID id = duplicate.map(TrainingPlanModels.Plan::id).orElseGet(UUID::randomUUID);
        var previousPlan = duplicate.orElse(null);
        var previousPhases = previousPlan == null ? List.<TrainingPlanModels.Phase>of() : repository.findPhases(id);
        var previousSessions = previousPlan == null ? List.<TrainingPlanModels.Session>of() : repository.findSessions(id, previousPlan.startDate(), previousPlan.endDate());
        var saved = new TrainingPlanModels.Plan(id, candidate.name(), candidate.goal(), candidate.startDate(), candidate.endDate(), candidate.raceDate(), candidate.targetTime(), candidate.description());
        if (replaced) {
            repository.updatePlan(saved);
            repository.deleteSessions(id);
        } else repository.insertPlan(saved);
        Set<UUID> reusedPhaseIds = new HashSet<>();
        var savedPhases = phases.stream().map(phase -> {
            UUID phaseId = previousPhases.stream().filter(old -> !reusedPhaseIds.contains(old.id()) && old.name().equals(phase.name())
                    && old.startDate().equals(phase.startDate()) && old.endDate().equals(phase.endDate())).map(TrainingPlanModels.Phase::id).findFirst().orElseGet(UUID::randomUUID);
            reusedPhaseIds.add(phaseId);
            return new TrainingPlanModels.Phase(phaseId, id, phase.name(), phase.startDate(), phase.endDate(), phase.description());
        }).toList();
        repository.replacePhases(id, savedPhases);
        Set<UUID> reusedSessionIds = new HashSet<>();
        for (var request : sessions) {
            UUID sessionId = previousSessions.stream().filter(old -> !reusedSessionIds.contains(old.id()) && old.date().equals(request.date())
                    && old.sport().equals(request.sport()) && old.sessionType().equals(request.sessionType()) && old.title().equals(request.title().trim()))
                    .map(TrainingPlanModels.Session::id).findFirst().orElseGet(UUID::randomUUID);
            reusedSessionIds.add(sessionId);
            repository.insertSession(session(sessionId, id, request));
        }
        return new ImportResult(view(saved), replaced);
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
                || !positiveInteger(request.targetTime())) throw invalid("Trainingsplan: Name, Ziel und gültiger Zeitraum sind erforderlich; Wettkampfdatum und Zielzeit müssen zum Zeitraum passen.");
    }

    private static void validatePhase(PhaseRequest phase, TrainingPlanModels.Plan plan) {
        if (phase == null || blank(phase.name()) || phase.startDate() == null || phase.endDate() == null
                || phase.endDate().isBefore(phase.startDate()) || !inRange(phase.startDate(), plan.startDate(), plan.endDate())
                || !inRange(phase.endDate(), plan.startDate(), plan.endDate())) throw invalid("Trainingsphase benötigt Name und gültigen Zeitraum innerhalb des Plans.");
    }

    private static void validateSession(SessionRequest session, TrainingPlanModels.Plan plan) {
        if (session == null || session.date() == null || !inRange(session.date(), plan.startDate(), plan.endDate())
                || session.sport() == null || !SPORTS.contains(session.sport()) || session.sessionType() == null || !TYPES.contains(session.sessionType()) || blank(session.title())
                || !positive(session.targetDistanceKm()) || !positive(session.targetPaceFrom()) || !positive(session.targetPaceTo())
                || !positiveInteger(session.targetDurationMinutes())
                || (session.targetRpe() != null && (!positiveInteger(session.targetRpe()) || session.targetRpe().compareTo(BigDecimal.TEN) > 0))
                || (session.targetPaceFrom() != null && session.targetPaceTo() != null && session.targetPaceFrom() > session.targetPaceTo())) {
            throw invalid("Trainingseinheit enthält ungültige Angaben (Datum, Sport, Typ, Distanz, Dauer, Pace oder RPE).");
        }
    }

    private static TrainingPlanModels.Session session(UUID id, UUID planId, SessionRequest request) {
        return new TrainingPlanModels.Session(id, planId, request.date(), request.sport(), request.sessionType(), request.title().trim(), request.targetDistanceKm(), integer(request.targetDurationMinutes()), request.targetPaceFrom(), request.targetPaceTo(), integer(request.targetRpe()), request.instructions(), request.notes());
    }

    private static boolean positive(Double value) { return value == null || (Double.isFinite(value) && value > 0); }
    private static boolean positiveInteger(BigDecimal value) { return value == null || (value.signum() > 0 && value.stripTrailingZeros().scale() <= 0 && value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0); }
    private static Integer integer(BigDecimal value) { return value == null ? null : value.intValueExact(); }
    private static BigDecimal decimal(Integer value) { return value == null ? null : BigDecimal.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean inRange(LocalDate date, LocalDate start, LocalDate end) { return !date.isBefore(start) && !date.isAfter(end); }
    private static TrainingPlanException invalid(String message) { return new TrainingPlanException(HttpStatus.BAD_REQUEST, "INVALID_TRAINING_PLAN", message); }
    private static TrainingPlanException notFound(String message) { return new TrainingPlanException(HttpStatus.NOT_FOUND, "TRAINING_PLAN_NOT_FOUND", message); }

    record PlanRequest(String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, BigDecimal targetTime, String description) { }
    record PhaseRequest(UUID id, String name, LocalDate startDate, LocalDate endDate, String description) { }
    record SessionRequest(LocalDate date, String sport, String sessionType, String title, Double targetDistanceKm, BigDecimal targetDurationMinutes,
                          Double targetPaceFrom, Double targetPaceTo, BigDecimal targetRpe, String instructions, String notes) { }
    record ImportDocument(Integer schemaVersion, ImportPlan plan, Boolean replaceExisting) {
        @JsonAnySetter void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unbekanntes Importfeld: " + name); }
    }
    record ImportPlan(String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, BigDecimal targetTime, String description,
                      List<ImportPhase> phases, List<ImportSession> sessions) {
        @JsonAnySetter void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unbekanntes Planfeld: " + name); }
    }
    record ImportPhase(String name, LocalDate startDate, LocalDate endDate, String description) {
        @JsonAnySetter void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unbekanntes Phasenfeld: " + name); }
    }
    record ImportSession(LocalDate date, String sport, String sessionType, String title, Double targetDistanceKm, BigDecimal targetDurationMinutes,
                         Double targetPaceFrom, Double targetPaceTo, BigDecimal targetRpe, String instructions, String notes) {
        @JsonAnySetter void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unbekanntes Einheitenfeld: " + name); }
    }
    record PlanDocument(int schemaVersion, ImportPlan plan) { }
    record ImportResult(PlanView plan, boolean replacedExisting) { }
    record PlanSummary(UUID id, String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, Integer targetTime, String description) {
        static PlanSummary from(TrainingPlanModels.Plan plan) { return new PlanSummary(plan.id(), plan.name(), plan.goal(), plan.startDate(), plan.endDate(), plan.raceDate(), plan.targetTime(), plan.description()); }
    }
    record PlanView(UUID id, String name, String goal, LocalDate startDate, LocalDate endDate, LocalDate raceDate, Integer targetTime, String description,
                    List<TrainingPlanModels.Phase> phases, List<TrainingPlanModels.Session> sessions) { }
}
