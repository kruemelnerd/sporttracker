package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TrainingPlanRepositoryTests {
    @Autowired
    private TrainingPlanRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void persistsPlanPhasesAndSessionsWithCalendarDates() {
        UUID id = UUID.randomUUID();
        LocalDate day = LocalDate.of(2027, 3, 14);
        try {
            repository.insertPlan(new TrainingPlanModels.Plan(id, "Plan", "Halbmarathon", day.minusMonths(5), day, day, 105, null));
            repository.replacePhases(id, List.of(new TrainingPlanModels.Phase(UUID.randomUUID(), id, "Taper", day.minusWeeks(2), day, null)));
            repository.insertSession(new TrainingPlanModels.Session(UUID.randomUUID(), id, day, "RUN", "RACE", "Wettkampf", 21.0975, 105, 4.9, 5.1, 8, null, null));

            var saved = repository.findPlan(id).orElseThrow();
            assertEquals(day.minusMonths(5), saved.startDate());
            assertEquals("Taper", repository.findPhases(id).getFirst().name());
            assertEquals(day, repository.findSessions(id, day, day).getFirst().date());
            assertTrue(repository.findSessions(id, day.minusDays(1), day.minusDays(1)).isEmpty());
        } finally {
            jdbc.update("DELETE FROM training_plan WHERE id = ?", id);
        }
    }
}
