package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Timestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
class DataManagementIntegrationTests {
    @Autowired
    private ApplicationContext context;

    @Autowired private DataManagementService management;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataMutationCoordinator mutations;
    @Autowired private SyncPauseRepository pauses;
    @Autowired private OriginalFileDeletionWorker fileDeletionWorker;

    @Test
    void deletionServiceIsAvailableForDatabaseBackedPreviewAndConfirm() {
        assertTrue(context.containsBean("dataManagementService"));
    }

    @Test
    @Transactional
    void providerDeleteOnlyRemovesExclusiveProviderActivitiesAndShowsProtectedAmbiguousOnes() {
        long garmin = activity("GARMIN");
        long manual = activity("MANUAL");
        long unknown = activity(null);
        long shared = activity("GARMIN");
        link("GARMIN", "g-1", garmin); link("GARMIN", "g-2", garmin);
        link("GARMIN", "manual", manual); link("GARMIN", "unknown", unknown);
        link("GARMIN", "shared", shared); link("INTERVALS_ICU", "shared-too", shared);

        var preview = management.preview(DataManagementService.Action.DELETE_GARMIN);

        assertEquals(4, preview.affectedActivities());
        assertEquals(1, preview.deletableActivities());
        assertEquals(3, preview.protectedActivities());
        assertEquals(1, preview.ambiguousActivities());

        var result = management.confirm(preview.action(), preview.token(), "GARMIN LÖSCHEN");

        assertEquals(1, result.activitiesDeleted());
        assertEquals(3, result.activitiesProtected());
        assertEquals(5, result.sourceAssociationsDeleted());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM activity", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_import_source WHERE provider = 'INTERVALS_ICU'", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM activity WHERE id = ?", Integer.class, manual) == 1);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM activity WHERE id = ?", Integer.class, unknown) == 1);
    }

    @Test
    @Transactional
    void stalePreviewAndWrongPhraseDoNotDeleteAnything() {
        long id = activity("GARMIN");
        link("GARMIN", "before", id);
        var preview = management.preview(DataManagementService.Action.DELETE_GARMIN);

        assertThrows(DataManagementService.DataManagementException.class,
                () -> management.confirm(preview.action(), preview.token(), "WRONG"));
        var stale = management.preview(DataManagementService.Action.DELETE_GARMIN);
        link("GARMIN", "after-preview", id);
        var conflict = assertThrows(DataManagementService.DataManagementException.class,
                () -> management.confirm(stale.action(), stale.token(), "GARMIN LÖSCHEN"));
        assertEquals(409, conflict.status());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity", Integer.class));

        var resetPreview = management.preview(DataManagementService.Action.RESET_APPLICATION_DATA);
        jdbc.update("INSERT INTO training_plan (id, name, goal, start_date, end_date) VALUES (?, 'new plan', 'goal', '2026-01-01', '2026-12-31')", UUID.randomUUID());
        var resetConflict = assertThrows(DataManagementService.DataManagementException.class,
                () -> management.confirm(resetPreview.action(), resetPreview.token(), "ALLE ANWENDUNGSDATEN LÖSCHEN"));
        assertEquals(409, resetConflict.status());
    }

    @Test
    void previewDoesNotObservePartialWriterState() throws Exception {
        try (var writer = mutations.acquire(); var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> assertThrows(DataManagementService.DataManagementException.class,
                    () -> management.preview(DataManagementService.Action.DELETE_ACTIVITIES)));
            assertEquals(409, result.get(2, TimeUnit.SECONDS).status());
        }
    }

    @Test
    @Transactional
    void intervalsDeleteRemovesOnlyIntervalsExclusiveActivitiesAndPreservesGarminSharedRows() {
        long intervals = activity("INTERVALS_ICU");
        long manual = activity("MANUAL");
        long shared = activity("INTERVALS_ICU");
        link("INTERVALS_ICU", "i-1", intervals);
        link("INTERVALS_ICU", "i-manual", manual);
        link("INTERVALS_ICU", "i-shared", shared);
        link("GARMIN", "g-shared", shared);

        var preview = management.preview(DataManagementService.Action.DELETE_INTERVALS_ICU);
        assertEquals(1, preview.deletableActivities());
        assertEquals(2, preview.protectedActivities());
        var result = management.confirm(preview.action(), preview.token(), "INTERVALS. ICU LÖSCHEN");

        assertEquals(1, result.activitiesDeleted());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM activity", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_import_source WHERE provider = 'GARMIN'", Integer.class));
        assertTrue(pauses.isPaused("INTERVALS_ICU"));
    }

    @Test
    void databaseFailureRollsBackRowsPauseAndFileCleanupJobs(@TempDir Path root) throws Exception {
        pauses.resume("GARMIN");
        long id = activity("GARMIN");
        Files.writeString(root.resolve("synthetic.fit"), "synthetic FIT fixture");
        jdbc.update("UPDATE import_file SET original_path = 'synthetic.fit' WHERE activity_id = ?", id);
        link("GARMIN", "rollback", id);
        DataManagementRepository failing = new DataManagementRepository(jdbc) {
            @Override public int deleteMappings(String provider) {
                super.deleteMappings(provider);
                throw new IllegalStateException("injected failure after destructive statement");
            }
        };
        var testWorker = new OriginalFileDeletionWorker(failing, mutations, root);
        var service = new DataManagementService(failing, pauses, mutations, transactionManager, testWorker);
        var preview = service.preview(DataManagementService.Action.DELETE_GARMIN);
        try {
            assertThrows(IllegalStateException.class, () -> service.confirm(preview.action(), preview.token(), "GARMIN LÖSCHEN"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity WHERE id = ?", Integer.class, id));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_import_source WHERE external_id = 'rollback'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pending_file_deletion WHERE relative_path = 'synthetic.fit' AND status <> 'DONE'", Integer.class));
            assertEquals(false, pauses.isPaused("GARMIN"));
            assertTrue(Files.exists(root.resolve("synthetic.fit")));
        } finally {
            jdbc.update("DELETE FROM activity WHERE id = ?", id);
            pauses.resume("GARMIN");
        }
    }

    @Test
    void deletesOriginalFilesOnlyAfterDatabaseCommitAndReportsActualCleanup(@TempDir Path root) throws Exception {
        String relative = "originals/after-commit.fit";
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "synthetic FIT fixture");
        long id = activity("MANUAL");
        jdbc.update("UPDATE import_file SET original_path = ? WHERE activity_id = ?", relative, id);
        var worker = new OriginalFileDeletionWorker(repositoryForTests(), mutations, root);
        var service = new DataManagementService(repositoryForTests(), pauses, mutations, transactionManager, worker);

        var preview = service.preview(DataManagementService.Action.DELETE_ACTIVITIES);
        var result = service.confirm(preview.action(), preview.token(), "ALLE AKTIVITÄTEN LÖSCHEN");

        assertFalse(Files.exists(file));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM activity WHERE id = ?", Integer.class, id));
        assertEquals(1, result.filesDeleted());
        assertEquals(repositoryForTests().pendingFileDeletionCount(), result.filesPending());
    }

    @Test
    @Transactional
    void activityResetPreservesPlansWhileFullResetRemovesPlansAndChildren() {
        activity("MANUAL");
        UUID plan = UUID.randomUUID();
        jdbc.update("INSERT INTO training_plan (id, name, goal, start_date, end_date) VALUES (?, 'plan', 'goal', '2026-01-01', '2026-12-31')", plan);
        jdbc.update("INSERT INTO training_phase (id, training_plan_id, name, start_date, end_date) VALUES (?, ?, 'phase', '2026-01-01', '2026-02-01')", UUID.randomUUID(), plan);
        jdbc.update("INSERT INTO planned_session (id, training_plan_id, session_date, sport, session_type, title) VALUES (?, ?, '2026-01-02', 'RUN', 'EASY', 'run')", UUID.randomUUID(), plan);

        var activityPreview = management.preview(DataManagementService.Action.DELETE_ACTIVITIES);
        management.confirm(activityPreview.action(), activityPreview.token(), "ALLE AKTIVITÄTEN LÖSCHEN");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM activity", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM training_plan", Integer.class));

        activity("MANUAL");
        var fullPreview = management.preview(DataManagementService.Action.RESET_APPLICATION_DATA);
        management.confirm(fullPreview.action(), fullPreview.token(), "ALLE ANWENDUNGSDATEN LÖSCHEN");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM training_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM training_phase", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM planned_session", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class) > 0);
        assertTrue(pausesPaused());
    }

    private boolean pausesPaused() { return pauses.isPaused("GARMIN") && pauses.isPaused("INTERVALS_ICU"); }

    private long activity(String source) {
        long id = jdbc.queryForObject("INSERT INTO activity (sport, start_time) VALUES ('running', ?) RETURNING id", Long.class,
                Timestamp.from(Instant.parse("2026-01-01T10:00:00Z").plusSeconds(System.nanoTime() % 100000)));
        String hash = String.format("%064x", id);
        jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes, source) VALUES (?, ?, 1, ?)", id, hash, source);
        return id;
    }

    private void link(String provider, String externalId, long id) {
        jdbc.update("INSERT INTO activity_import_source (provider, external_id, activity_id) VALUES (?, ?, ?)", provider, externalId, id);
    }

    private DataManagementRepository repositoryForTests() { return new DataManagementRepository(jdbc); }
}
