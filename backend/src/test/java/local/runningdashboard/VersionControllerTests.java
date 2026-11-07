package local.runningdashboard;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class VersionControllerTests {
    private static final String STABLE = "https://ghcr.io/v2/kruemelnerd/sporttracker/manifests/stable";
    private static final String COMMIT = "a".repeat(40);
    private static final String DIGEST = "sha256:" + "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final String INDEX = "{\"schemaVersion\":2,\"manifests\":[]}";
    private static final MediaType INDEX_TYPE = MediaType.parseMediaType("application/vnd.oci.image.index.v1+json");
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final Clock clock = mock(Clock.class);

    @Test
    void getReturnsOnlyTheDefinedDtoAndDisablesHttpCaching() throws Exception {
        server.expect(requestTo(STABLE)).andRespond(withSuccess(INDEX, INDEX_TYPE));
        mvc(DIGEST, COMMIT).perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(8)))
                .andExpect(jsonPath("$.applicationVersion").value("1.2.3"))
                .andExpect(jsonPath("$.installedCommit").value(COMMIT))
                .andExpect(jsonPath("$.installedDigest").value(DIGEST))
                .andExpect(jsonPath("$.latestCommit").value(nullValue()))
                .andExpect(jsonPath("$.latestDigest").value(matchesPattern("sha256:[0-9a-f]{64}")))
                .andExpect(jsonPath("$.updateStatus").value("DIFFERENT_RELEASE"))
                .andExpect(jsonPath("$.lastCheckedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.errorStatus").value(nullValue()));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void apiExposesProvenUpdatesButRetainsMetadataWhenGitHubIsUnavailable(boolean failure) throws Exception {
        String latestCommit = "c".repeat(40);
        String latestDigest = "sha256:" + "d".repeat(64);
        var registry = mock(GhcrRegistryClient.class);
        when(registry.fetchStable()).thenReturn(new GhcrRegistryClient.Release(latestDigest, latestCommit));
        server.expect(requestTo("https://api.github.com/repos/kruemelnerd/sporttracker/compare/" + COMMIT + "..." + latestCommit + "?per_page=1"))
                .andRespond(failure ? withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private-upstream-exception")
                        : withSuccess("{\"status\":\"ahead\",\"ahead_by\":1,\"behind_by\":0,\"base_commit\":{\"sha\":\"" + COMMIT
                                + "\"},\"merge_base_commit\":{\"sha\":\"" + COMMIT + "\"}}", MediaType.APPLICATION_JSON));
        var properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("commit", COMMIT);
        when(clock.instant()).thenReturn(NOW);
        var service = new VersionService(registry, new GitHubCompareClient(builder), new BuildProperties(properties), DIGEST, clock);
        MockMvcBuilders.standaloneSetup(new VersionController(service)).build().perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(8)))
                .andExpect(jsonPath("$.updateStatus").value(failure ? "DIFFERENT_RELEASE" : "UPDATE_AVAILABLE"))
                .andExpect(jsonPath("$.installedCommit").value(COMMIT))
                .andExpect(jsonPath("$.installedDigest").value(DIGEST))
                .andExpect(jsonPath("$.latestCommit").value(latestCommit))
                .andExpect(jsonPath("$.latestDigest").value(latestDigest))
                .andExpect(jsonPath("$.errorStatus").value(nullValue()))
                .andExpect(content().string(not(containsString("private-upstream-exception"))));
        server.verify();
    }

    @Test
    void localDevelopmentWithoutDigestOrBuildCommitHasAnExplicitUnknownFallback() throws Exception {
        server.expect(requestTo(STABLE)).andRespond(withSuccess(INDEX, INDEX_TYPE));
        mvc("", "").perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationVersion").value("1.2.3"))
                .andExpect(jsonPath("$.installedCommit").value(nullValue()))
                .andExpect(jsonPath("$.installedDigest").value(nullValue()))
                .andExpect(jsonPath("$.updateStatus").value("UNKNOWN"));
        server.verify();
    }

    @Test
    void registryCredentialsAndRemoteErrorsNeverBecomeApiFieldsOrMessages() throws Exception {
        server.expect(requestTo(STABLE)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate",
                "Bearer realm=\"https://ghcr.io/token\",service=\"ghcr.io\",scope=\"repository:kruemelnerd/sporttracker:pull\""));
        server.expect(requestTo("https://ghcr.io/token?service=ghcr.io&scope=repository:kruemelnerd/sporttracker:pull"))
                .andRespond(withSuccess("{\"token\":\"private-registry-token\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(STABLE)).andExpect(header("Authorization", "Bearer private-registry-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private-registry-token private-upstream-exception"));
        var response = mvc(DIGEST, COMMIT).perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(8)))
                .andExpect(jsonPath("$.installedCommit").value(COMMIT))
                .andExpect(jsonPath("$.installedDigest").value(DIGEST))
                .andExpect(jsonPath("$.latestDigest").value(nullValue()))
                .andExpect(jsonPath("$.updateStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.errorStatus").value("AUTHENTICATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("private-"));
        assertFalse(response.contains("Authorization"));
        assertFalse(response.contains("Exception"));
        assertFalse(response.contains("stackTrace"));
        server.verify();
    }

    @Test
    void refreshQueryUsesTheSameCacheAndServerSideCooldown() throws Exception {
        server.expect(requestTo(STABLE)).andRespond(withSuccess(INDEX, INDEX_TYPE));
        server.expect(requestTo(STABLE)).andRespond(withSuccess(INDEX, INDEX_TYPE));
        var mvc = mvc(DIGEST, COMMIT);
        mvc.perform(get("/api/version")).andExpect(status().isOk());
        mvc.perform(get("/api/version").param("refresh", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastCheckedAt").value(NOW.toString()));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        mvc.perform(get("/api/version").param("refresh", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastCheckedAt").value(NOW.plusSeconds(60).toString()));
        mvc.perform(get("/api/version").param("refresh", "true")).andExpect(status().isOk());
        mvc.perform(get("/api/version")).andExpect(status().isOk());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousApiCallsShareARegistryCheckWithoutBlockingHealthOrReadiness(boolean failure) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.expect(requestTo(STABLE)).andRespond(request -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return failure ? withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(request)
                    : withSuccess(INDEX, INDEX_TYPE).createResponse(request);
        });
        var mvc = mvc(DIGEST, COMMIT);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> mvc.perform(get("/api/version")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    mvc.perform(get("/api/health")).andExpect(status().isOk());
                    mvc.perform(get("/api/ready")).andExpect(status().isOk());
                });
                var pending = new ArrayList<Future<String>>();
                for (int i = 0; i < 16; i++) {
                    String refresh = Boolean.toString(i % 2 == 0);
                    pending.add(executor.submit(() -> mvc.perform(get("/api/version").param("refresh", refresh))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.errorStatus").value(failure ? "REGISTRY_UNAVAILABLE" : null))
                            .andReturn().getResponse().getContentAsString()));
                }
                release.countDown();
                String expected = first.get(5, TimeUnit.SECONDS);
                for (var future : pending) assertEquals(expected, future.get(5, TimeUnit.SECONDS));
                mvc.perform(get("/api/health")).andExpect(status().isOk());
                mvc.perform(get("/api/ready")).andExpect(status().isOk());
            } finally {
                release.countDown();
            }
        }
        server.verify();
    }

    @Test
    void springWiresGeneratedBuildInfoAndComposeDigestWithoutFetchingAtStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProjectInfoAutoConfiguration.class))
                .withUserConfiguration(VersionService.class, VersionController.class)
                .withBean(GhcrRegistryClient.class, () -> new GhcrRegistryClient(builder))
                .withBean(GitHubCompareClient.class, () -> new GitHubCompareClient(builder))
                .withPropertyValues("SPORTTRACKER_IMAGE_DIGEST=" + DIGEST)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(VersionController.class));
                    // No HTTP expectation until after startup: an eager check would fail this test.
                    server.expect(requestTo(STABLE)).andRespond(withSuccess(INDEX, INDEX_TYPE));
                    var result = context.getBean(VersionService.class).version(false);
                    assertEquals("0.1.0-SNAPSHOT", result.applicationVersion());
                    assertEquals(DIGEST, result.installedDigest());
                    server.verify();
                });
    }

    private MockMvc mvc(String digest, String commit) {
        var properties = new Properties();
        properties.setProperty("version", "1.2.3");
        properties.setProperty("commit", commit);
        when(clock.instant()).thenReturn(NOW);
        var service = new VersionService(new GhcrRegistryClient(builder), new GitHubCompareClient(builder), new BuildProperties(properties), digest, clock);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any())).thenReturn(true);
        return MockMvcBuilders.standaloneSetup(new VersionController(service), new HealthController(), new ReadinessController(jdbc)).build();
    }
}
