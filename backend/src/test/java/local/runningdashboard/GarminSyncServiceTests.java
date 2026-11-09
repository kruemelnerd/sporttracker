package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-garmin-sync-tests")
class GarminSyncServiceTests {
    private static final String HASH = "d54a2ed4389fdf07955b5c92d166860c5c9138ba9dceea9ccfa623667a834180";
    @Autowired private ImportService imports;
    @Autowired private ActivityImportSourceRepository mappings;
    @Autowired private GarminSyncStateRepository states;
    @Autowired private JdbcTemplate jdbc;
    private byte[] fit;

    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", HASH);
        jdbc.update("DELETE FROM garmin_sync_state");
        try (InputStream input = new ClassPathResource("fit/synthetic-running.fit").getInputStream()) { fit = input.readAllBytes(); }
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", HASH);
        jdbc.update("DELETE FROM garmin_sync_state");
    }

    @Test
    void importsOnceAndSkipsMappedIdsOnRepeatedSync() {
        var source = new FakeSource(List.of(new ActivityImportSource.RemoteActivity("771", "trail_running")), fit);
        var service = service(source);

        var first = service.synchronize();
        var second = service.synchronize();

        assertEquals(1, first.imported());
        assertEquals(1, second.alreadyPresent());
        assertEquals(1, source.downloads);
        assertTrue(mappings.findActivityId("GARMIN", "771").isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", Integer.class, HASH));
        assertEquals(Instant.parse("2026-10-19T10:00:00Z"), states.find().orElseThrow().lastSuccessfulSyncAt());
    }

    @Test
    void skipsUnrecognizedSportTypeWithoutDownloadingFit() {
        var source = new FakeSource(List.of(new ActivityImportSource.RemoteActivity("unknown", "run_walk")), fit);

        var result = service(source).synchronize();

        assertEquals(1, result.skipped());
        assertEquals(0, result.imported());
        assertEquals(0, source.downloads);
        assertEquals(LocalDate.parse("2026-07-21"), source.oldest);
    }

    @Test
    void continuesAfterOneInvalidFitButDoesNotMarkPartialWindowSuccessful() {
        ActivityImportSource source = new ActivityImportSource() {
            @Override public String provider() { return "GARMIN"; }
            @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
                return List.of(new RemoteActivity("bad", "running"), new RemoteActivity("good", "virtual_run"));
            }
            @Override public ActivityFile downloadActivityFile(String id) {
                return new ActivityFile("bad".equals(id) ? new byte[] {1, 2, 3} : fit, "application/octet-stream", null);
            }
        };

        var result = service(source).synchronize();

        assertEquals(1, result.failed());
        assertEquals(1, result.imported());
        assertEquals(null, states.find().orElseThrow().lastSuccessfulSyncAt());
    }

    @Test
    void incompletePaginationNeverAdvancesTheSuccessfulWindow() {
        ActivityImportSource source = new FakeSource(List.of(), fit) {
            @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
                throw new GarminClientException("PAGINATION_INCOMPLETE");
            }
        };

        var result = service(source).synchronize();

        assertEquals("PAGINATION_INCOMPLETE", result.error());
        assertEquals(null, states.find().orElseThrow().lastSuccessfulSyncAt());
    }

    @Test
    void refusesToLinkAnExternalIdThatBecameMappedToAnotherActivity() {
        Long otherId = jdbc.queryForObject("INSERT INTO activity (sport, start_time) VALUES ('cycling', ?) RETURNING id", Long.class,
                java.sql.Timestamp.from(Instant.parse("2026-10-18T08:00:00Z")));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var racingMappings = new ActivityImportSourceRepository(jdbc) {
            @Override public java.util.Optional<Long> findActivityId(String provider, String externalId) {
                return reads.incrementAndGet() == 1 ? java.util.Optional.empty() : java.util.Optional.of(otherId);
            }
            @Override public boolean link(String provider, String externalId, long activityId) { return false; }
        };
        try {
            var source = new FakeSource(List.of(new ActivityImportSource.RemoteActivity("race", "running")), fit);

            var result = service(source, racingMappings).synchronize();

            assertEquals(1, result.failed());
            assertEquals("EXTERNAL_ID_CONFLICT", result.error());
            assertTrue(mappings.findActivityId("GARMIN", "race").isEmpty());
            assertEquals("cycling", jdbc.queryForObject("SELECT sport FROM activity WHERE id = ?", String.class, otherId));
        } finally {
            jdbc.update("DELETE FROM activity WHERE id = ?", otherId);
        }
    }

    @Test
    void rejectsParallelManualAndAutomaticSyncWithoutQueuing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = new FakeSource(List.of(), fit) {
            @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
                oldestCalls++;
                entered.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return List.of();
            }
        };
        var service = service(source);
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(service.startAsync(executor::execute));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(!service.startAsync(executor::execute));
            assertTrue(!service.synchronize().started());
            release.countDown();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertEquals(1, source.oldestCalls);
    }

    private GarminSyncService service(ActivityImportSource source) {
        return service(source, mappings);
    }

    private GarminSyncService service(ActivityImportSource source, ActivityImportSourceRepository repository) {
        var properties = new GarminPersonalApiProperties(true, "test-key", "https://personal.connectapi.garmin.com",
                Duration.ofMinutes(30), 90, 50, 20, 52_428_800, Duration.ofSeconds(5), Duration.ofSeconds(30));
        return new GarminSyncService(source, imports, repository, properties, states,
                Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private static class FakeSource implements ActivityImportSource {
        private final List<RemoteActivity> activities;
        private final byte[] fit;
        private int downloads;
        private LocalDate oldest;
        int oldestCalls;
        private FakeSource(List<RemoteActivity> activities, byte[] fit) { this.activities = activities; this.fit = fit; }
        @Override public String provider() { return "GARMIN"; }
        @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) { this.oldest = oldest; oldestCalls++; return activities; }
        @Override public ActivityFile downloadActivityFile(String externalId) { downloads++; return new ActivityFile(fit, "application/octet-stream", null); }
    }
}
