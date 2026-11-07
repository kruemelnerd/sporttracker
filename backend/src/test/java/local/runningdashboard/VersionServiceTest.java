package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VersionServiceTest {
    static final String BASE = "https://ghcr.io/v2/kruemelnerd/sporttracker/";
    static final String COMMIT = "a".repeat(40);
    static final String OTHER_COMMIT = "b".repeat(40);
    static final String INSTALLED = "sha256:" + "c".repeat(64);
    static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    final MutableClock clock = new MutableClock();
    final RestClient.Builder builder = RestClient.builder();
    final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void productionIdentityComesFromBuildInfoAndConfiguredDigest() throws Exception {
        String latest = expectRelease(OTHER_COMMIT);
        var result = service(INSTALLED, COMMIT).version(false);
        assertEquals("1.2.3", result.applicationVersion());
        assertEquals(COMMIT, result.installedCommit());
        assertEquals(INSTALLED, result.installedDigest());
        assertEquals(OTHER_COMMIT, result.latestCommit());
        assertEquals(latest, result.latestDigest());
        assertEquals(VersionStatus.DIFFERENT_RELEASE, result.updateStatus());
        assertEquals(NOW, result.lastCheckedAt());
        assertNull(result.errorStatus());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "sha256:invalid", "secret-not-a-digest"})
    void localOrInvalidDigestIsUnknownWithoutInventingAnInstalledIdentity(String digest) throws Exception {
        expectRelease(OTHER_COMMIT);
        var result = service(digest, COMMIT).version(false);
        assertEquals("1.2.3", result.applicationVersion());
        assertEquals(COMMIT, result.installedCommit());
        assertNull(result.installedDigest());
        assertEquals(VersionStatus.UNKNOWN, result.updateStatus());
        server.verify();
    }

    @Test
    void identicalDigestsAreCurrentEvenWithoutCommitMetadata() throws Exception {
        String latest = expectRelease(null);
        var result = service(latest, "").version(false);
        assertEquals(VersionStatus.CURRENT, result.updateStatus());
        assertNull(result.installedCommit());
        assertNull(result.latestCommit());
        server.verify();
    }

    @Test
    void identicalCommitWithNewImageIsARebuildNotAnUpdate() throws Exception {
        expectRelease(COMMIT);
        assertEquals(VersionStatus.REBUILD_AVAILABLE, service(INSTALLED, COMMIT).version(false).updateStatus());
        server.verify();
    }

    @ParameterizedTest
    @CsvSource(value = {";", "a;", ";b", "secret-not-a-commit;secret-not-a-commit"}, delimiter = ';', nullValues = "")
    void missingOrInvalidCommitMetadataCannotEstablishReleaseOrder(String installed, String latest) throws Exception {
        expectRelease(latest == null ? null : latest.repeat(latest.length() == 1 ? 40 : 1));
        var result = service(INSTALLED, installed == null ? null : installed.repeat(installed.length() == 1 ? 40 : 1)).version(false);
        assertEquals(VersionStatus.DIFFERENT_RELEASE, result.updateStatus());
        if (installed == null || installed.startsWith("secret")) assertNull(result.installedCommit());
        if (latest == null || latest.startsWith("secret")) assertNull(result.latestCommit());
        server.verify();
    }

    @Test
    void successfulResultsAreCachedForSixHoursAndReloadedAtExpiry() throws Exception {
        String first = expectRelease(COMMIT);
        String second = expectRelease(OTHER_COMMIT);
        var service = service(INSTALLED, COMMIT);
        var initial = service.version(false);
        clock.advance(Duration.ofHours(6).minusSeconds(1));
        assertEquals(initial, service.version(false));
        clock.advance(Duration.ofSeconds(1));
        var refreshed = service.version(false);
        assertEquals(first, initial.latestDigest());
        assertEquals(second, refreshed.latestDigest());
        assertEquals(NOW.plus(Duration.ofHours(6)), refreshed.lastCheckedAt());
        server.verify();
    }

    @Test
    void failuresReturnLocalIdentityAndRetryAfterFiveMinutes() throws Exception {
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private-upstream-error"));
        String latest = expectRelease(OTHER_COMMIT);
        var service = service(INSTALLED, COMMIT);
        var failed = service.version(false);
        assertEquals("1.2.3", failed.applicationVersion());
        assertEquals(COMMIT, failed.installedCommit());
        assertEquals(INSTALLED, failed.installedDigest());
        assertEquals(VersionStatus.UNKNOWN, failed.updateStatus());
        assertEquals(GhcrRegistryClient.ErrorStatus.REGISTRY_UNAVAILABLE, failed.errorStatus());
        assertNull(failed.latestDigest());
        assertNull(failed.latestCommit());
        assertEquals(NOW, failed.lastCheckedAt());
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertEquals(failed, service.version(false));
        clock.advance(Duration.ofSeconds(1));
        var recovered = service.version(false);
        assertEquals(latest, recovered.latestDigest());
        assertNull(recovered.errorStatus());
        server.verify();
    }

    @Test
    void failedRefreshDoesNotPresentOldRegistryMetadataAsCurrent() throws Exception {
        expectRelease(COMMIT);
        server.expect(requestTo(BASE + "manifests/stable")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        var service = service(INSTALLED, COMMIT);
        assertNotNull(service.version(false).latestDigest());
        clock.advance(Duration.ofMinutes(1));
        var failed = service.version(true);
        assertNull(failed.latestDigest());
        assertEquals(VersionStatus.UNKNOWN, failed.updateStatus());
        assertEquals(GhcrRegistryClient.ErrorStatus.ACCESS_DENIED, failed.errorStatus());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualRefreshBypassesTtlButCannotRepeatWithinOneMinute(boolean initiallyFailed) throws Exception {
        if (initiallyFailed) server.expect(requestTo(BASE + "manifests/stable")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        else expectRelease(COMMIT);
        String latest = expectRelease(OTHER_COMMIT);
        var service = service(INSTALLED, COMMIT);
        var initial = service.version(true);
        clock.advance(Duration.ofSeconds(59));
        assertEquals(initial, service.version(true));
        clock.advance(Duration.ofSeconds(1));
        var refreshed = service.version(true);
        assertEquals(latest, refreshed.latestDigest());
        assertEquals(NOW.plusSeconds(60), refreshed.lastCheckedAt());
        assertEquals(refreshed, service.version(true));
        assertEquals(refreshed, service.version(false));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentRequestsShareTheSameCheckEvenForForcedRefresh(boolean failure) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.expect(requestTo(BASE + "manifests/stable")).andRespond(request -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            clock.advance(Duration.ofMinutes(2));
            return failure ? withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(request)
                    : withSuccess("{\"schemaVersion\":2,\"manifests\":[]}",
                            MediaType.parseMediaType("application/vnd.oci.image.index.v1+json")).createResponse(request);
        });
        var service = service(INSTALLED, COMMIT);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.version(false));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var pending = new java.util.ArrayList<java.util.concurrent.Future<VersionResponse>>();
                for (int i = 0; i < 16; i++) {
                    boolean refresh = i % 2 == 0;
                    pending.add(executor.submit(() -> service.version(refresh)));
                }
                release.countDown();
                var expected = first.get(5, TimeUnit.SECONDS);
                assertEquals(NOW.plusSeconds(120), expected.lastCheckedAt());
                for (var future : pending) assertEquals(expected, future.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        server.verify();
    }

    VersionService service(String digest, String commit) {
        return new VersionService(new GhcrRegistryClient(builder), buildInfo(commit), digest, clock);
    }

    static BuildProperties buildInfo(String commit) {
        var properties = new Properties();
        properties.setProperty("version", "1.2.3");
        if (commit != null) properties.setProperty("commit", commit);
        return new BuildProperties(properties);
    }

    String expectRelease(String commit) throws Exception {
        String config = commit == null ? "{\"config\":{}}"
                : "{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + commit + "\"}}}";
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + digest(config) + "\"},\"layers\":[]}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType("application/vnd.oci.image.manifest.v1+json")));
        server.expect(requestTo(BASE + "blobs/" + digest(config))).andRespond(withSuccess(config, MediaType.APPLICATION_JSON));
        return digest(manifest);
    }

    private static String digest(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    static class MutableClock extends Clock {
        private Instant now = NOW;
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
