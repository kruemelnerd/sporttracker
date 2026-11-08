package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GhcrRegistryClientTest {
    private static final String BASE = "https://ghcr.io/v2/kruemelnerd/sporttracker/";
    private static final String OCI = "application/vnd.oci.image.manifest.v1+json";
    private static final String COMMIT = "a".repeat(40);
    private static final String INDEX = "application/vnd.oci.image.index.v1+json";
    private static final String CHALLENGE = "Bearer realm=\"https://ghcr.io/token\",service=\"ghcr.io\",scope=\"repository:kruemelnerd/sporttracker:pull\"";

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION_REQUIRED", "403,ACCESS_DENIED", "404,NOT_FOUND", "429,RATE_LIMITED", "500,REGISTRY_UNAVAILABLE", "503,REGISTRY_UNAVAILABLE", "302,INVALID_RESPONSE"})
    void classifiesHttpFailuresWithoutReadingRemoteErrors(int status, String expected) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("remote-private-error"));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(expected, failure.status().name());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("remote-private-error"));
        server.verify();
    }

    @Test
    void networkFailureIsSanitized() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withException(new IOException("private connection detail")));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.NETWORK_ERROR, failure.status());
        assertNull(failure.getCause());
        server.verify();
    }

    @Test
    void expiredDeadlineIsTimeoutEvenWhenTransportOnlyReportsAClosedStream() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withException(new IOException("private closed stream detail")));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class,
                () -> new GhcrRegistryClient(builder, Duration.ofNanos(1)).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.TIMEOUT, failure.status());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private"));
        server.verify();
    }

    @Test
    void timeoutIsSanitized() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withException(new HttpTimeoutException("private timeout detail")));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals("TIMEOUT", failure.status().name());
        assertNull(failure.getCause());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredTimeoutActuallyBoundsAStalledHttpResponse(boolean sendHeaders) throws Exception {
        var release = new CountDownLatch(1);
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            http.setExecutor(executor);
            http.createContext("/", exchange -> {
                try {
                    if (sendHeaders) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                    }
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            http.start();
            var factory = GhcrRegistryClient.requestFactory(Duration.ofMillis(100), Duration.ofMillis(100));
            var builder = RestClient.builder().requestFactory((uri, method) -> factory.createRequest(
                    URI.create("http://127.0.0.1:" + http.getAddress().getPort() + uri.getPath()), method));
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    var failure = assertThrows(GhcrRegistryClient.RegistryException.class,
                            () -> new GhcrRegistryClient(builder, Duration.ofMillis(100)).fetchStable());
                    assertEquals("TIMEOUT", failure.status().name());
                });
            } finally {
                release.countDown();
                http.stop(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json"})
    void indexWithoutAnUnambiguousCiPlatformStillHasItsOwnDigest(String type) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String index = "{\"schemaVersion\":2,\"manifests\":[{\"platform\":{\"os\":\"linux\",\"architecture\":\"arm64\"}}]}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(index, MediaType.parseMediaType(type)));
        var result = new GhcrRegistryClient(builder).fetchStable();
        assertEquals(digest(index), result.digest());
        assertNull(result.commit());
        server.verify();
    }

    @Test
    void ambiguousPlatformCannotProduceAnArbitraryCommit() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String platform = "{\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}";
        String index = "{\"schemaVersion\":2,\"manifests\":[" + platform + "," + platform + "]}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(index, MediaType.parseMediaType(INDEX)));
        var result = new GhcrRegistryClient(builder).fetchStable();
        assertEquals(digest(index), result.digest());
        assertNull(result.commit());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../outside", "https://evil.invalid", "sha256:short"})
    void descriptorCannotEscapeTheRegistryRepository(String maliciousDigest) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + maliciousDigest + "\"}}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType(OCI)));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @Test
    void rejectsTamperedConfigEvenWhenItClaimsAValidCommit() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String expectedConfig = "{\"config\":{}}";
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + digest(expectedConfig) + "\"}}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType(OCI)));
        server.expect(requestTo(BASE + "blobs/" + digest(expectedConfig)))
                .andRespond(withSuccess("{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + COMMIT + "\"}}}", MediaType.APPLICATION_JSON));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer realm=\"https://evil.invalid/token\",service=\"ghcr.io\"", "Basic realm=\"ghcr.io\"",
            "Bearer realm=\"https://ghcr.io/token\",service=\"other\"",
            "Bearer realm=\"https://ghcr.io/token\",service=\"ghcr.io\",scope=\"repository:kruemelnerd/sporttracker:pull,push\"",
            "Bearer realm=\"https://ghcr.io/token\",realm=\"https://evil.invalid\",service=\"ghcr.io\""})
    void neverFollowsUntrustedOrWriteScopedChallenges(String challenge) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", challenge));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.AUTHENTICATION_REQUIRED, failure.status());
        server.verify();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void rejectedBearerIsNotRetriedOrExposedInExceptionsOrLogs(CapturedOutput output) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", CHALLENGE));
        server.expect(requestTo("https://ghcr.io/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull"))
                .andRespond(withSuccess("{\"access_token\":\"secret-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "manifests/stable"))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret-token"));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.AUTHENTICATION_REQUIRED, failure.status());
        var stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertFalse(stack.toString().contains("secret-token"));
        assertFalse(output.getAll().contains("secret-token"));
        server.verify();
    }

    @Test
    void privatePackageDenialIsNotConfusedWithRepositoryVisibility() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", CHALLENGE));
        server.expect(requestTo("https://ghcr.io/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.ACCESS_DENIED, failure.status());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"token\":\"bad\\r\\nvalue\"}", "not-json"})
    void rejectsMalformedTokenResponses(String body) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", CHALLENGE));
        server.expect(requestTo("https://ghcr.io/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{}", "{\"schemaVersion\":1}", "{\"schemaVersion\":2,\"mediaType\":\"unsupported\"}"})
    void rejectsMalformedManifests(String body) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(body, MediaType.parseMediaType(OCI)));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @Test
    void refusesMismatchedRegistryDigest() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess("{}", MediaType.parseMediaType(OCI)).header("Docker-Content-Digest", "sha256:" + "b".repeat(64)));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @Test
    void boundsMetadataResponseSize() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(" ".repeat(1024 * 1024 + 1), MediaType.parseMediaType(OCI)));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "https://example.invalid/commit", "secret-token"})
    void missingOrInvalidRevisionDoesNotInvalidateTheDigest(String revision) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String config = revision.isEmpty() ? "{\"config\":{}}"
                : "{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + revision + "\"}}}";
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.docker.container.image.v1+json\",\"digest\":\"" + digest(config) + "\"},\"layers\":[]}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType("application/vnd.docker.distribution.manifest.v2+json")));
        server.expect(requestTo(BASE + "blobs/" + digest(config)))
                .andRespond(withSuccess(config, MediaType.APPLICATION_JSON));
        var result = new GhcrRegistryClient(builder).fetchStable();
        assertEquals(digest(manifest), result.digest());
        assertNull(result.commit());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json"})
    void followsAnonymousPullChallengeAndKeepsIndexDigestInsteadOfPlatformDigest(String indexType) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String config = "{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + COMMIT + "\"}}}";
        String platform = "{\"schemaVersion\":2,\"mediaType\":\"" + OCI + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + digest(config) + "\"},\"layers\":[]}";
        String index = "{\"schemaVersion\":2,\"mediaType\":\"" + indexType + "\",\"manifests\":[{\"digest\":\"" + digest(platform) + "\",\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}},{\"digest\":\"sha256:" + "b".repeat(64) + "\",\"platform\":{\"os\":\"unknown\",\"architecture\":\"unknown\"}}]}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", CHALLENGE));
        server.expect(requestTo("https://ghcr.io/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"token\":\"secret-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "manifests/stable"))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess(index, MediaType.parseMediaType(indexType)).header("Docker-Content-Digest", digest(index)));
        server.expect(requestTo(BASE + "manifests/" + digest(platform)))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess(platform, MediaType.parseMediaType(OCI)));
        server.expect(requestTo(BASE + "blobs/" + digest(config)))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withSuccess(config, MediaType.APPLICATION_JSON));

        var result = new GhcrRegistryClient(builder).fetchStable();

        assertEquals(digest(index), result.digest());
        assertNotEquals(digest(platform), result.digest());
        assertEquals(COMMIT, result.commit());
        server.verify();
    }

    @Test
    void readsRedirectedGhcrConfigWithoutForwardingThePullToken() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String config = "{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + COMMIT + "\"}}}";
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + digest(config) + "\"}}";
        String location = "https://pkg-containers.githubusercontent.com/ghcrblobs10/blobs/" + digest(config) + "?sig=a%2Fb&se=2026-11-07";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", CHALLENGE));
        server.expect(requestTo("https://ghcr.io/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull"))
                .andRespond(withSuccess("{\"token\":\"secret-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType(OCI)));
        server.expect(requestTo(BASE + "blobs/" + digest(config)))
                .andExpect(header("Authorization", "Bearer secret-token"))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT).header("Location", location));
        server.expect(requestTo(location)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(config, MediaType.APPLICATION_JSON));

        var result = new GhcrRegistryClient(builder).fetchStable();
        assertEquals(digest(manifest), result.digest());
        assertEquals(COMMIT, result.commit());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.invalid/", "http://pkg-containers.githubusercontent.com/ghcrblobs10/blobs/",
            "https://pkg-containers.githubusercontent.com.evil.invalid/ghcrblobs10/blobs/",
            "https://user@pkg-containers.githubusercontent.com/ghcrblobs10/blobs/",
            "https://pkg-containers.githubusercontent.com:8443/ghcrblobs10/blobs/",
            "https://pkg-containers.githubusercontent.com/other/", "", "/relative/"})
    void configRedirectsCannotEscapeTheTrustedHttpsBlobLocation(String prefix) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String configDigest = digest("{\"config\":{}}");
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + configDigest + "\"}}";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType(OCI)));
        server.expect(requestTo(BASE + "blobs/" + configDigest))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT).header("Location", prefix + configDigest));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void redirectedConfigStillRequiresItsDigestAndCannotRedirectAgain(boolean redirectAgain) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String configDigest = digest("{\"config\":{}}");
        String manifest = "{\"schemaVersion\":2,\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + configDigest + "\"}}";
        String location = "https://pkg-containers.githubusercontent.com/ghcrblobs10/blobs/" + configDigest;
        server.expect(requestTo(BASE + "manifests/stable"))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType(OCI)));
        server.expect(requestTo(BASE + "blobs/" + configDigest))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT).header("Location", location));
        server.expect(requestTo(location)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(redirectAgain ? withStatus(HttpStatus.TEMPORARY_REDIRECT).header("Location", "https://evil.invalid/")
                        : withSuccess("{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + COMMIT + "\"}}}", MediaType.APPLICATION_JSON));
        var failure = assertThrows(GhcrRegistryClient.RegistryException.class, () -> new GhcrRegistryClient(builder).fetchStable());
        assertEquals(GhcrRegistryClient.ErrorStatus.INVALID_RESPONSE, failure.status());
        server.verify();
    }

    @Test
    void hashesExactManifestBytesAndReadsRevisionFromConfigWithoutDownloadingLayers() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String config = "{\"config\":{\"Labels\":{\"org.opencontainers.image.revision\":\"" + COMMIT + "\"}}}";
        String manifest = "{\n  \"schemaVersion\":2,\"mediaType\":\"" + OCI + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + digest(config) + "\"},\"layers\":[{\"digest\":\"sha256:" + "f".repeat(64) + "\",\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\",\"size\":999999999}]}\n";
        server.expect(requestTo(BASE + "manifests/stable"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(header("Accept", org.hamcrest.Matchers.containsString(OCI)))
                .andExpect(header("Accept", org.hamcrest.Matchers.containsString(INDEX)))
                .andExpect(header("Accept", org.hamcrest.Matchers.containsString("application/vnd.docker.distribution.manifest.v2+json")))
                .andExpect(header("Accept", org.hamcrest.Matchers.containsString("application/vnd.docker.distribution.manifest.list.v2+json")))
                .andRespond(withSuccess(manifest, MediaType.parseMediaType(OCI)));
        server.expect(requestTo(BASE + "blobs/" + digest(config)))
                .andRespond(withSuccess(config, MediaType.APPLICATION_JSON));

        var result = new GhcrRegistryClient(builder).fetchStable();

        assertEquals(digest(manifest), result.digest());
        assertEquals(COMMIT, result.commit());
        server.verify();
    }

    private static String digest(String text) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
