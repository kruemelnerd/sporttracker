package local.runningdashboard;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TrainingPlanController {
    private final TrainingPlanService service;

    TrainingPlanController(TrainingPlanService service) {
        this.service = service;
    }

    @GetMapping("/api/training-plans")
    List<TrainingPlanService.PlanSummary> list() { return service.list(); }

    @PostMapping("/api/training-plans")
    ResponseEntity<TrainingPlanService.PlanView> create(@RequestBody TrainingPlanService.PlanRequest request) {
        var plan = service.create(request);
        return ResponseEntity.created(URI.create("/api/training-plans/" + plan.id())).body(plan);
    }

    @GetMapping("/api/training-plans/{id}")
    TrainingPlanService.PlanView get(@PathVariable UUID id) { return service.get(id); }

    @PutMapping("/api/training-plans/{id}")
    TrainingPlanService.PlanView update(@PathVariable UUID id, @RequestBody TrainingPlanService.PlanRequest request) { return service.update(id, request); }

    @DeleteMapping("/api/training-plans/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }

    @PutMapping("/api/training-plans/{id}/phases")
    List<TrainingPlanModels.Phase> phases(@PathVariable UUID id, @RequestBody List<TrainingPlanService.PhaseRequest> phases) { return service.replacePhases(id, phases); }

    @PostMapping("/api/training-plans/{id}/sessions")
    ResponseEntity<TrainingPlanModels.Session> createSession(@PathVariable UUID id, @RequestBody TrainingPlanService.SessionRequest request) {
        var session = service.createSession(id, request);
        return ResponseEntity.created(URI.create("/api/training-plans/" + id + "/sessions/" + session.id())).body(session);
    }

    @GetMapping("/api/training-plans/{id}/sessions")
    List<TrainingPlanModels.Session> sessions(@PathVariable UUID id, @RequestParam LocalDate from, @RequestParam LocalDate to) { return service.sessions(id, from, to); }

    @PutMapping("/api/training-plans/{id}/sessions/{sessionId}")
    TrainingPlanModels.Session updateSession(@PathVariable UUID id, @PathVariable UUID sessionId, @RequestBody TrainingPlanService.SessionRequest request) {
        return service.updateSession(id, sessionId, request);
    }

    @DeleteMapping("/api/training-plans/{id}/sessions/{sessionId}")
    ResponseEntity<Void> deleteSession(@PathVariable UUID id, @PathVariable UUID sessionId) { service.deleteSession(id, sessionId); return ResponseEntity.noContent().build(); }
}
