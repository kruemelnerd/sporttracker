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
                        first ? 1.66d : 2.77d, null, null, null, null, null, List.of(), List.of());
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
                1.66d, null, null, null, null, null, List.of(), List.of());
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
                600d, 600d, -1d, 1.66d, null, null, null, null, null, List.of(), List.of());
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
