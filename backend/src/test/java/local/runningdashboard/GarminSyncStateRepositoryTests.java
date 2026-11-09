package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class GarminSyncStateRepositoryTests {
    @Autowired private GarminSyncStateRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsOnlyAggregateStatusWithoutChangingActivityData() {
        var state = new GarminSyncStateRepository.SyncState(
                Instant.parse("2026-11-09T08:00:00Z"), Instant.parse("2026-11-09T08:01:00Z"),
                Instant.parse("2026-11-09T07:59:00Z"), Instant.parse("2026-11-09T08:30:00Z"),
                12, 3, 8, 1, 0, null);
        Integer activitiesBefore = jdbc.queryForObject("SELECT count(*) FROM activity", Integer.class);
        Integer sourcesBefore = jdbc.queryForObject("SELECT count(*) FROM activity_import_source", Integer.class);

        assertTrue(repository.find().isEmpty());
        repository.save(state);

        assertEquals(state, repository.find().orElseThrow());
        assertEquals(activitiesBefore, jdbc.queryForObject("SELECT count(*) FROM activity", Integer.class));
        assertEquals(sourcesBefore, jdbc.queryForObject("SELECT count(*) FROM activity_import_source", Integer.class));
    }
}
