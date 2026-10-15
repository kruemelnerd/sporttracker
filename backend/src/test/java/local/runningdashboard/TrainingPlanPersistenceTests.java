package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TrainingPlanPersistenceTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayCreatesTrainingPlanTablesWithoutChangingFitTables() {
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('training_plan')", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('training_phase')", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('planned_session')", String.class));
        assertNotNull(jdbc.queryForObject("SELECT to_regclass('activity')", String.class));
    }
}
