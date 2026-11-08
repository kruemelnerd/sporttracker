package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubCompareClientTest {
    private static final String INSTALLED = "a".repeat(40);
    private static final String LATEST = "b".repeat(40);
    private static final String URL = "https://api.github.com/repos/kruemelnerd/sporttracker/compare/"
            + INSTALLED + "..." + LATEST + "?per_page=1";
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void confirmsAncestryOnlyUsingAnonymousReadOnlyRequestToTheFixedRepository() {
        server.expect(requestTo(URL))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header("User-Agent", "PACEBASE-version-check"))
                .andRespond(withSuccess(ahead(), MediaType.APPLICATION_JSON));
        assertEquals(GitHubCompareClient.Comparison.AHEAD, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"behind", "diverged", "identical"})
    void otherHistoriesAreNeverEvidenceOfANewerRelease(String status) {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"status\":\"" + status + "\"}", MediaType.APPLICATION_JSON));
        assertEquals(GitHubCompareClient.Comparison.NOT_AHEAD, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "main", "abc123", "a...b", "../other", "https://evil.invalid/repo", "user:branch",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaG", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/../other"})
    void onlyFullValidatedShasCanReachTheNetwork(String invalid) {
        var client = new GitHubCompareClient(builder);
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, client.compare(invalid, LATEST));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, client.compare(INSTALLED, invalid));
        server.verify();
    }

    @Test
    void missingCommitsAndIdenticalCommitsNeedNoRequest() {
        var client = new GitHubCompareClient(builder);
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, client.compare(null, LATEST));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, client.compare(INSTALLED, null));
        assertEquals(GitHubCompareClient.Comparison.NOT_AHEAD, client.compare(INSTALLED, INSTALLED));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 401, 403, 404, 422, 429, 500, 502, 503})
    void httpErrorsAndRedirectsAreNotRetriedOrReturnedAsEvidence(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status))
                .header("Location", "https://evil.invalid/").body("private-token internal exception"));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @Test
    void networkErrorsAreControlled() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("private connection detail")));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @Test
    void timeoutsAreControlled() {
        server.expect(requestTo(URL)).andRespond(withException(new HttpTimeoutException("private timeout detail")));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredTimeoutBoundsStalledHeadersAndBody(boolean sendHeaders) throws Exception {
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
            var client = new GitHubCompareClient(RestClient.builder().requestFactory((uri, method) -> factory.createRequest(
                    URI.create("http://127.0.0.1:" + http.getAddress().getPort() + uri.getPath()), method)));
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, client.compare(INSTALLED, LATEST)));
            } finally {
                release.countDown();
                http.stop(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "null", "{}", "{\"status\":\"unknown\"}", "{\"status\":\"ahead\"}"})
    void malformedOrIncompleteResponsesDoNotProveAncestry(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"base", "merge-base", "no-ahead", "negative-ahead", "behind", "missing-behind", "string-count", "overflow-behind"})
    void contradictoryAheadResponsesCannotProveAncestry(String variant) {
        String body = switch (variant) {
            case "base" -> ahead().replace("\"base_commit\":{\"sha\":\"" + INSTALLED, "\"base_commit\":{\"sha\":\"" + LATEST);
            case "merge-base" -> ahead().replace("\"merge_base_commit\":{\"sha\":\"" + INSTALLED, "\"merge_base_commit\":{\"sha\":\"" + LATEST);
            case "no-ahead" -> ahead().replace("\"ahead_by\":2", "\"ahead_by\":0");
            case "negative-ahead" -> ahead().replace("\"ahead_by\":2", "\"ahead_by\":-1");
            case "behind" -> ahead().replace("\"behind_by\":0", "\"behind_by\":1");
            case "missing-behind" -> ahead().replace(",\"behind_by\":0", "");
            case "overflow-behind" -> ahead().replace("\"behind_by\":0", "\"behind_by\":18446744073709551616");
            default -> ahead().replace("\"ahead_by\":2", "\"ahead_by\":\"2\"");
        };
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    @Test
    void boundsResponseSizeWithoutFollowingPaginationLinks() {
        server.expect(requestTo(URL)).andRespond(withSuccess(ahead() + " ".repeat(1024 * 1024), MediaType.APPLICATION_JSON)
                .header("Link", "<https://evil.invalid/>; rel=\"next\""));
        assertEquals(GitHubCompareClient.Comparison.UNAVAILABLE, new GitHubCompareClient(builder).compare(INSTALLED, LATEST));
        server.verify();
    }

    private static String ahead() {
        return "{\"status\":\"ahead\",\"ahead_by\":2,\"behind_by\":0,\"total_commits\":2,"
                + "\"base_commit\":{\"sha\":\"" + INSTALLED + "\"},\"merge_base_commit\":{\"sha\":\"" + INSTALLED + "\"}}";
    }
}
