package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class SyncPauseRepositoryTests {
    @Autowired
    private SyncPauseRepository pauses;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsProviderPauseRowsWithoutOverwritingOtherProviders() {
        pauses.resume("GARMIN");
        pauses.resume("INTERVALS_ICU");
        pauses.pause("GARMIN");

        assertEquals(true, pauses.isPaused("GARMIN"));
        assertEquals(false, pauses.isPaused("INTERVALS_ICU"));

        pauses.resume("GARMIN");
        assertEquals(false, pauses.isPaused("GARMIN"));
    }

    @Test
    @Transactional
    void missingPersistentPauseStateFailsClosed() {
        jdbc.update("DELETE FROM data_management_sync_pause WHERE provider = 'GARMIN'");
        assertTrue(pauses.isPaused("GARMIN"));
    }
}
