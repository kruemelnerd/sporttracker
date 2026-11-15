package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties = "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-identity-tests")
class ImportIdentityTests {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ActivityImportSourceRepository sourceMappings;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM activity WHERE start_time = ?", java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")));
        jdbc.update("DELETE FROM activity WHERE start_time = ?", java.sql.Timestamp.from(Instant.parse("2026-02-01T10:00:00Z")));
        jdbc.update("DELETE FROM activity WHERE start_time = ?", java.sql.Timestamp.from(Instant.parse("2026-02-02T10:00:00Z")));
    }

    @Test
    void keepsDifferentActivitiesWithTheSameStartTime() {
        AtomicInteger calls = new AtomicInteger();
        GarminFitFixtureAdapter adapter = new GarminFitFixtureAdapter() {
            @Override
            public DecodedActivity decode(InputStream ignored) {
                boolean first = calls.getAndIncrement() == 0;
                return new DecodedActivity("running", null, Instant.parse("2026-01-01T10:00:00Z"),
                        first ? 600d : 1800d, first ? 600d : 1800d, first ? 1000d : 5000d,
                        first ? 1.66d : 2.77d, null, null, null, null, null, List.of(), List.of(), List.of(), List.of());
            }
        };
        ImportService imports = new ImportService(adapter, jdbc, new TransactionTemplate(transactionManager),
                Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-identity-tests").toString(),
                new ImportProperties(10, 10_485_760, 104_857_600));

        assertEquals("IMPORTED", imports.importFiles(List.of(file("first.fit", new byte[] {1}))).outcomes().getFirst().status());
        assertEquals("IMPORTED", imports.importFiles(List.of(file("second.fit", new byte[] {2}))).outcomes().getFirst().status());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM activity WHERE start_time = ?", Integer.class,
                java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z"))));
    }

    @Test
    void doesNotArbitrarilyLinkAnAmbiguousFitDuplicate() throws Exception {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        jdbc.update("INSERT INTO activity (sport, start_time, elapsed_seconds, distance_meters) VALUES ('running', ?, 600, 1000), ('running', ?, 600, 1000)",
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(start));
        var decoded = new GarminFitFixtureAdapter.DecodedActivity("running", null, start, 600d, 600d, 1000d,
                1.66d, null, null, null, null, null, List.of(), List.of(), List.of(), List.of());
        var imports = new ImportService(new GarminFitFixtureAdapter() {
            @Override public DecodedActivity decode(InputStream ignored) { return decoded; }
        }, jdbc, new TransactionTemplate(transactionManager),
                Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-identity-tests").toString(),
                new ImportProperties(10, 10_485_760, 104_857_600));

        var outcome = imports.importFiles(List.of(file("ambiguous.fit", new byte[] {3}))).outcomes().getFirst();

        assertEquals("DUPLICATE", outcome.status());
        assertEquals("AMBIGUOUS_ACTIVITY_MATCH", outcome.code());
        assertNull(outcome.activityId());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM activity WHERE start_time = ?", Integer.class,
                java.sql.Timestamp.from(start)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM import_file WHERE sha256 = ?", Integer.class,
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[] {3}))));
    }

    @Test
    void classifiesNonDuplicateIntegrityViolationsAsFailed() {
        var decoded = new GarminFitFixtureAdapter.DecodedActivity("running", null, Instant.parse("2026-01-02T10:00:00Z"),
                600d, 600d, -1d, 1.66d, null, null, null, null, null, List.of(), List.of(), List.of(), List.of());
        var imports = new ImportService(new GarminFitFixtureAdapter() {
            @Override public DecodedActivity decode(InputStream ignored) { return decoded; }
        }, jdbc, new TransactionTemplate(transactionManager),
                Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-identity-tests").toString(),
                new ImportProperties(10, 10_485_760, 104_857_600));

        var outcome = imports.importFiles(List.of(file("invalid-measurement.fit", new byte[] {4}))).outcomes().getFirst();

        assertEquals("FAILED", outcome.status());
        assertEquals("IMPORT_FAILED", outcome.code());
    }

    @Test
    void persistsRunningDynamicsWorkoutIntervalsAndTimerPauses() {
        Instant start = Instant.parse("2026-02-01T10:00:00Z");
        var record = new GarminFitFixtureAdapter.DecodedRecord(start, 0d, 0d, null, null, null, null,
                null, null, null, 1.234d, 7.8d, 8.3d, 240d, 49.7d, 12d, 250d);
        var lap = new GarminFitFixtureAdapter.DecodedLap(1, 10d, 400d, null, null, null, null, null,
                1, 1, 0d, 10d, 0d, 400d);
        var step = new GarminFitFixtureAdapter.DecodedWorkoutStep(1, "Fast", "distance", null, 400d,
                null, null, "active", null, null, null, null, null);
        var pause = new GarminFitFixtureAdapter.DecodedPause(12d, 15d);
        var decoded = new GarminFitFixtureAdapter.DecodedActivity("running", null, start, 30d, 27d,
                1500d, null, null, null, null, null, null, List.of(record), List.of(lap),
                List.of(step), List.of(pause));
        var imports = new ImportService(new GarminFitFixtureAdapter() {
            @Override public DecodedActivity decode(InputStream ignored) { return decoded; }
        }, jdbc, new TransactionTemplate(transactionManager),
                Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-identity-tests").toString(),
                new ImportProperties(10, 10_485_760, 104_857_600));

        var outcome = imports.importFiles(List.of(file("dynamics.fit", new byte[] {5}))).outcomes().getFirst();
        long activityId = outcome.activityId();

        assertEquals("IMPORTED", outcome.status());
        assertEquals(1.234d, jdbc.queryForObject("SELECT step_length_m FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(7.8d, jdbc.queryForObject("SELECT vertical_oscillation_cm FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(8.3d, jdbc.queryForObject("SELECT vertical_ratio_percent FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(240d, jdbc.queryForObject("SELECT ground_contact_time_ms FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(49.7d, jdbc.queryForObject("SELECT ground_contact_balance_percent FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(12d, jdbc.queryForObject("SELECT temperature_celsius FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(250d, jdbc.queryForObject("SELECT power_watts FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT workout_step_index FROM lap WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT repetition_number FROM lap WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_workout_step WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(12d, jdbc.queryForObject("SELECT start_elapsed_seconds FROM activity_pause WHERE activity_id = ?", Double.class, activityId));
        assertEquals(15d, jdbc.queryForObject("SELECT end_elapsed_seconds FROM activity_pause WHERE activity_id = ?", Double.class, activityId));
    }

    @Test
    void changedByteReimportFillsMissingFitDataWithoutReplacingExistingEnrichment() {
        Instant start = Instant.parse("2026-02-02T10:00:00Z");
        var sparseRecord = new GarminFitFixtureAdapter.DecodedRecord(start, 0d, 0d, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        var richRecord = new GarminFitFixtureAdapter.DecodedRecord(start, 0d, 0d, null, null, null, null,
                null, null, null, 1.5d, 8d, 8d, 250d, 50d, 10d, 200d);
        var lap = new GarminFitFixtureAdapter.DecodedLap(1, 10d, 400d, null, null, null, null, null,
                1, 1, 0d, 10d, 0d, 400d);
        var step = new GarminFitFixtureAdapter.DecodedWorkoutStep(1, "Fast", "distance", null, 400d,
                null, null, "active", null, null, null, null, null);
        var sparse = new GarminFitFixtureAdapter.DecodedActivity("running", null, start, 30d, 27d, 1500d,
                null, null, null, null, null, null, List.of(sparseRecord), List.of(), List.of(), List.of());
        var rich = new GarminFitFixtureAdapter.DecodedActivity("running", null, start, 30d, 27d, 1500d,
                null, null, null, null, null, null, List.of(richRecord), List.of(lap), List.of(step),
                List.of(new GarminFitFixtureAdapter.DecodedPause(12d, 15d)));
        AtomicInteger calls = new AtomicInteger();
        var imports = new ImportService(new GarminFitFixtureAdapter() {
            @Override public DecodedActivity decode(InputStream ignored) { return calls.getAndIncrement() == 0 ? sparse : rich; }
        }, jdbc, new TransactionTemplate(transactionManager),
                Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-identity-tests").toString(),
                new ImportProperties(10, 10_485_760, 104_857_600));

        long activityId = imports.importFiles(List.of(file("first.fit", new byte[] {6}))).outcomes().getFirst().activityId();
        jdbc.update("UPDATE activity SET garmin_activity_name = 'Keep this name', garmin_training_effect = 2.5 WHERE id = ?", activityId);
        var duplicate = imports.importFiles(List.of(file("reexport.fit", new byte[] {7}))).outcomes().getFirst();

        assertEquals("DUPLICATE", duplicate.status());
        assertEquals(1.5d, jdbc.queryForObject("SELECT step_length_m FROM activity_record WHERE activity_id = ?", Double.class, activityId));
        assertEquals("Keep this name", jdbc.queryForObject("SELECT garmin_activity_name FROM activity WHERE id = ?", String.class, activityId));
        assertEquals(2.5d, jdbc.queryForObject("SELECT garmin_training_effect FROM activity WHERE id = ?", Double.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_record WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lap WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_workout_step WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_pause WHERE activity_id = ?", Integer.class, activityId));
    }

    @Test
    void sourceMappingsAreUniqueAndReferenceAnExistingActivity() {
        Long activityId = jdbc.queryForObject("INSERT INTO activity (sport, start_time) VALUES ('running', ?) RETURNING id",
                Long.class, java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")));

        assertTrue(sourceMappings.link("INTERVALS_ICU", "source-1", activityId));
        assertEquals(activityId, sourceMappings.findActivityId("INTERVALS_ICU", "source-1").orElseThrow());
        org.junit.jupiter.api.Assertions.assertFalse(sourceMappings.link("INTERVALS_ICU", "source-1", activityId));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO activity_import_source (provider, external_id, activity_id) VALUES ('INTERVALS_ICU', 'source-1', ?)", activityId));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO activity_import_source (provider, external_id, activity_id) VALUES ('INTERVALS_ICU', 'source-2', ?)", -1));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity_import_source WHERE provider = 'INTERVALS_ICU' AND external_id = 'source-1'", Integer.class));
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", bytes);
    }
}
