package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.core.task.TaskExecutor;

@SpringBootTest(properties = "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-intervals-sync-tests")
class IntervalsIcuSyncServiceTests {
    private static final String FIXTURE_HASH = "d54a2ed4389fdf07955b5c92d166860c5c9138ba9dceea9ccfa623667a834180";
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportService imports;
    @Autowired private ActivityImportSourceRepository mappings;

    private byte[] fit;

    @BeforeEach
    void removeFixtureAndReadBytes() throws Exception {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", FIXTURE_HASH);
        try (InputStream input = new ClassPathResource("fit/synthetic-running.fit").getInputStream()) { fit = input.readAllBytes(); }
    }

    @AfterEach
    void cleanupFixture() {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", FIXTURE_HASH);
    }

    @Test
    void importsRunTrailRunAndVirtualRunThroughTheExistingFitPipeline() throws Exception {
        var source = new FakeSource(List.of(activity("1", "Run"), activity("2", "TrailRun"), activity("3", "VirtualRun")))
                .file("1", gzip(fit)).file("2", gzip(fit)).file("3", gzip(fit));

        var result = service(source).synchronize();

        assertEquals(3, result.checked());
        assertEquals(1, result.imported());
        assertEquals(2, result.alreadyPresent());
        assertEquals(1, activityCount());
        assertEquals(mappings.findActivityId("INTERVALS_ICU", "1").orElseThrow(), mappings.findActivityId("INTERVALS_ICU", "2").orElseThrow());
        assertEquals(mappings.findActivityId("INTERVALS_ICU", "1").orElseThrow(), mappings.findActivityId("INTERVALS_ICU", "3").orElseThrow());
    }

    @Test
    void acceptsAnHttpClientAlreadyDecodedGzipContentEncoding() {
        var source = new FakeSource(List.of(activity("decoded", "Run"))).file("decoded", fit);

        var result = service(source).synchronize();

        assertEquals(1, result.imported());
        assertEquals(1, activityCount());
    }

    @Test
    void skipsNonRunWithoutDownloadingItsFile() {
        var source = new FakeSource(List.of(activity("bike", "Ride")));

        var result = service(source).synchronize();

        assertEquals(1, result.skipped());
        assertEquals("NON_RUNNING", result.activities().getFirst().code());
        assertEquals(0, source.downloads);
    }

    @Test
    void rejectsAnActivityWithoutAnExternalIdWithoutCallingItsFileEndpoint() {
        var source = new FakeSource(List.of(activity("", "Run")));

        var result = service(source).synchronize();

        assertEquals(1, result.failed());
        assertEquals("INVALID_EXTERNAL_ACTIVITY", result.activities().getFirst().code());
        assertEquals(0, source.downloads);
    }

    @Test
    void skipsAnAlreadyMappedExternalIdWithoutDownloadingIt() {
        Long activityId = jdbc.queryForObject("INSERT INTO activity (sport, start_time) VALUES ('running', ?) RETURNING id", Long.class,
                java.sql.Timestamp.from(NOW));
        mappings.link("INTERVALS_ICU", "known", activityId);
        var source = new FakeSource(List.of(activity("known", "Run")));

        var result = service(source).synchronize();

        assertEquals(1, result.alreadyPresent());
        assertEquals(0, source.downloads);
    }

    @Test
    void linksAManuallyImportedFitToItsExistingActivity() throws Exception {
        imports.importFiles(List.of(new MockMultipartFile("files", "manual.fit", "application/octet-stream", fit)));
        var source = new FakeSource(List.of(activity("manual", "Run"))).file("manual", gzip(fit));

        var result = service(source).synchronize();

        assertEquals(0, result.imported());
        assertEquals(1, result.alreadyPresent());
        assertEquals(1, activityCount());
        assertTrue(mappings.findActivityId("INTERVALS_ICU", "manual").isPresent());
    }

    @Test
    void aDamagedFileDoesNotStopLaterActivities() throws Exception {
        var source = new FakeSource(List.of(activity("broken", "Run"), activity("valid", "Run")))
                .file("broken", new byte[] {1, 2, 3}).file("valid", gzip(fit));

        var result = service(source).synchronize();

        assertEquals(2, result.checked());
        assertEquals(1, result.failed());
        assertEquals(1, result.imported());
        assertEquals("INVALID_GZIP", result.activities().getFirst().code());
        assertEquals(1, activityCount());
    }

    @Test
    void skipsGpxAndUnavailableOriginalFiles() throws Exception {
        byte[] gpx = gzip("<?xml version=\"1.0\"?><gpx></gpx>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] tcx = gzip("<TrainingCenterDatabase></TrainingCenterDatabase>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var source = new FakeSource(List.of(activity("gpx", "Run"), activity("tcx", "TrailRun"), activity("unavailable", "Run")))
                .file("gpx", gpx).file("tcx", tcx).unavailable("unavailable");

        var result = service(source).synchronize();

        assertEquals(3, result.skipped());
        assertEquals("NO_FIT_FILE", result.activities().get(0).code());
        assertEquals("NO_FIT_FILE", result.activities().get(1).code());
        assertEquals("FILE_NOT_AVAILABLE", result.activities().get(2).code());
        assertEquals(0, activityCount());
    }

    @Test
    void honorsRetryAfterWithoutStartingAnotherRequest() {
        var source = new FakeSource(List.of()) {
            @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
                listCalls++;
                throw new IntervalsIcuClientException("RATE_LIMITED", Duration.ofSeconds(120));
            }
        };
        var sync = service(source);

        var first = sync.synchronize();
        var second = sync.synchronize();

        assertEquals(1, first.failed());
        assertFalse(second.started());
        assertEquals(NOW.plusSeconds(120), sync.retryAfterUntil());
        assertEquals(1, source.listCalls);
    }

    @Test
    void resumesAfterFitWasImportedButExternalMappingWasInterrupted() throws Exception {
        var source = new FakeSource(List.of(activity("resume", "Run"))).file("resume", gzip(fit));
        var failingMappings = new ActivityImportSourceRepository(jdbc) {
            private boolean fail = true;
            @Override public boolean link(String provider, String externalId, long activityId) {
                if (fail) { fail = false; throw new IllegalStateException("simulated interruption"); }
                return super.link(provider, externalId, activityId);
            }
        };

        var interrupted = service(source, failingMappings).synchronize();
        var resumed = service(source, mappings).synchronize();

        assertEquals(1, interrupted.failed());
        assertEquals(1, resumed.alreadyPresent());
        assertTrue(mappings.findActivityId("INTERVALS_ICU", "resume").isPresent());
        assertEquals(1, activityCount());
    }

    @Test
    void usesInitialLookbackUntilACompleteSyncThenUsesFourteenDayRollingWindow() {
        var source = new FakeSource(List.of());
        var sync = service(source);

        sync.synchronize();
        sync.synchronize();

        assertEquals(LocalDate.parse("2026-07-21"), source.windows.get(0)[0]);
        assertEquals(LocalDate.parse("2026-10-05"), source.windows.get(1)[0]);
        assertEquals(LocalDate.parse("2026-10-19"), source.windows.get(0)[1]);
    }

    @Test
    void usesTheConfiguredLocalCalendarDateForIntervalsDateRanges() {
        var source = new FakeSource(List.of());
        var properties = new IntervalsIcuProperties(true, "test-key", "http://127.0.0.1:1", Duration.ofMinutes(15), 90);
        var sync = new IntervalsIcuSyncService(source, imports, mappings, properties,
                Clock.fixed(Instant.parse("2026-10-19T23:30:00Z"), ZoneOffset.UTC), ZoneId.of("Europe/Berlin"));

        sync.synchronize();

        assertEquals(LocalDate.parse("2026-10-20"), source.windows.getFirst()[1]);
    }

    @Test
    void preventsASecondConcurrentSync() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = new FakeSource(List.of()) {
            @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return List.of();
            }
        };
        var sync = service(source);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(sync::synchronize);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = sync.synchronize();
            assertFalse(second.started());
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS).started());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void asynchronousTriggerUsesTheSameSyncServiceAndReservesTheOnlyRun() {
        var source = new FakeSource(List.of());
        var sync = service(source);
        var queued = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        TaskExecutor executor = queued::set;

        assertTrue(sync.startAsync(executor));
        assertFalse(sync.synchronize().started());
        queued.get().run();

        assertEquals(1, source.listCalls);
        assertTrue(sync.lastResult().started());
    }

    @Test
    void startupAndScheduledTriggersOnlyEnqueueTheSharedSyncService() {
        var source = new FakeSource(List.of());
        var sync = service(source);
        var queued = new java.util.ArrayDeque<Runnable>();
        var triggers = new IntervalsIcuSyncTriggers(sync, queued::add);

        triggers.onApplicationReady();
        assertTrue(sync.running());
        triggers.onSchedule();
        assertEquals(1, queued.size());
        queued.remove().run();

        assertEquals(1, source.listCalls);
        assertFalse(sync.running());
        assertTrue(sync.lastResult().started());
    }

    private IntervalsIcuSyncService service(ActivityImportSource source) {
        return service(source, mappings);
    }

    private IntervalsIcuSyncService service(ActivityImportSource source, ActivityImportSourceRepository repository) {
        var properties = new IntervalsIcuProperties(true, "test-key", "http://127.0.0.1:1", Duration.ofMinutes(15), 90);
        return new IntervalsIcuSyncService(source, imports, repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private int activityCount() {
        return jdbc.queryForObject("SELECT count(*) FROM activity WHERE start_time = ?", Integer.class,
                java.sql.Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
    }

    private static ActivityImportSource.RemoteActivity activity(String id, String type) {
        return new ActivityImportSource.RemoteActivity(id, type);
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(output)) { gzip.write(bytes); }
        return output.toByteArray();
    }

    private static class FakeSource implements ActivityImportSource {
        private final List<RemoteActivity> activities;
        private final Map<String, ActivityFile> files = new HashMap<>();
        private final List<LocalDate[]> windows = new java.util.ArrayList<>();
        private int downloads;
        int listCalls;
        private final java.util.Set<String> unavailable = new java.util.HashSet<>();

        FakeSource(List<RemoteActivity> activities) { this.activities = activities; }
        FakeSource file(String id, byte[] body) { files.put(id, new ActivityFile(body, "application/gzip", null)); return this; }
        FakeSource unavailable(String id) { unavailable.add(id); return this; }

        @Override public String provider() { return "INTERVALS_ICU"; }
        @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
            listCalls++;
            windows.add(new LocalDate[] {oldest, newest});
            return activities;
        }
        @Override public ActivityFile downloadActivityFile(String externalId) {
            downloads++;
            if (unavailable.contains(externalId)) throw new IntervalsIcuClientException("FILE_NOT_AVAILABLE", null);
            return files.get(externalId);
        }
    }
}
