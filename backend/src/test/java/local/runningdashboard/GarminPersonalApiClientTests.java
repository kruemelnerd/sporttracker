package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GarminPersonalApiClientTests {
    private HttpServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test
    void authenticatesAndSearchesAllPagesUsingGarminActivityTypeStructure() throws Exception {
        AtomicInteger tokens = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> {
            assertEquals("ApiKey private-test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            tokens.incrementAndGet();
            respond(exchange, 200, "opaque-token");
        });
        server.createContext("/activitylist-service/activities/search/activities", exchange -> {
            assertEquals("Bearer opaque-token", exchange.getRequestHeaders().getFirst("Authorization"));
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query.contains("startDate=2026-01-01"));
            assertTrue(query.contains("endDate=2026-01-31"));
            if (query.contains("start=0")) respond(exchange, 200,
                    "[{\"activityId\":101,\"activityType\":{\"typeKey\":\"running\"}},{\"activityId\":102,\"activityType\":{\"typeKey\":\"trail_running\"}}]");
            else respond(exchange, 200, "[{\"activityId\":103,\"activityType\":{\"typeKey\":\"virtual_run\"}}]");
        });
        server.start();
        var client = client(2, 3);

        var result = client.listActivities(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"));

        assertEquals(3, result.activities().size());
        assertTrue(result.complete());
        assertEquals(1, tokens.get());
        assertEquals("running", result.activities().getFirst().type());
        assertEquals("trail_running", result.activities().get(1).type());
        assertEquals("virtual_run", result.activities().get(2).type());
    }

    @Test
    void rejectsTheObsoleteActivityTypeDtoShape() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> respond(exchange, 200,
                "[{\"activityId\":101,\"activityTypeDTO\":{\"typeKey\":\"running\"}}]"));
        server.start();

        var failure = assertThrows(GarminClientException.class,
                () -> client(10, 2).listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("INVALID_RESPONSE", failure.code());
    }

    @Test
    void rejectsSearchWindowWhenPageLimitMayHaveTruncatedResults() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> respond(exchange, 200,
                "[{\"activityId\":12,\"activityType\":{\"typeKey\":\"running\"}}]"));
        server.start();
        var client = client(1, 1);

        var failure = assertThrows(GarminClientException.class,
                () -> client.listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("PAGINATION_INCOMPLETE", failure.code());
    }

    @Test
    void renewsExpiredBearerOnceWithoutExposingKeyOrTokenInFailure() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        AtomicInteger searches = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> {
            int issued = tokenRequests.incrementAndGet();
            respond(exchange, 200, "token-" + issued);
        });
        server.createContext("/activitylist-service/activities/search/activities", exchange -> {
            if (searches.incrementAndGet() == 1) respond(exchange, 401, "private response body");
            else respond(exchange, 403, "private response body");
        });
        server.start();
        var failure = assertThrows(GarminClientException.class, () -> client(10, 2)
                .listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("ACCESS_DENIED", failure.code());
        assertEquals("ACCESS_DENIED", failure.getMessage());
        assertEquals(2, tokenRequests.get());
        assertEquals(2, searches.get());
        assertTrue(!failure.getMessage().contains("private-test-key"));
        assertTrue(!failure.getMessage().contains("token-"));
    }

    @Test
    void mapsRateLimitAndClampsRetryAfter() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "999999");
            respond(exchange, 429, "private response body");
        });
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2)
                .listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("RATE_LIMITED", failure.code());
        assertEquals(Duration.ofHours(6), failure.retryAfter());
        assertEquals("RATE_LIMITED", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"404,FILE_NOT_AVAILABLE", "500,GARMIN_UNAVAILABLE", "503,GARMIN_UNAVAILABLE"})
    void mapsNotFoundAndServerFailuresToSanitizedCodes(int status, String expectedCode) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> respond(exchange, status, "private activity payload"));
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2)
                .listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals(expectedCode, failure.code());
        assertEquals(expectedCode, failure.getMessage());
    }

    @Test
    void rejectsAnOversizedSearchResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> respond(exchange, 200, "[\"" + "x".repeat(100) + "\"]"));
        server.start();
        var properties = new GarminPersonalApiProperties(true, "private-test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMinutes(30), 90, 10, 2, 20, Duration.ofSeconds(1), Duration.ofSeconds(1));

        var failure = assertThrows(GarminClientException.class,
                () -> new GarminPersonalApiClient(properties).listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("RESPONSE_TOO_LARGE", failure.code());
    }

    @Test
    void loadsOnlyValidatedTrainingMetricsFromActivitySummary() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activity-service/activity/123", exchange -> {
            assertEquals("Bearer token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"summaryDTO":{"trainingEffect":3.4,"anaerobicTrainingEffect":0,"activityTrainingLoad":175.5,
                    "movingDuration":999,"averageRunCadence":999}}
                    """);
        });
        server.start();

        var metrics = client(10, 2).activityTrainingMetrics("123");

        assertEquals(3.4, metrics.aerobicTrainingEffect());
        assertEquals(0.0, metrics.anaerobicTrainingEffect());
        assertEquals(175.5, metrics.trainingLoad());
    }

    @Test
    void leavesMissingAndInvalidOptionalTrainingMetricsNull() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activity-service/activity/123", exchange -> respond(exchange, 200,
                "{\"summaryDTO\":{\"trainingEffect\":6,\"anaerobicTrainingEffect\":null,\"activityTrainingLoad\":-1}}"));
        server.start();

        var metrics = client(10, 2).activityTrainingMetrics("123");

        assertEquals(null, metrics.aerobicTrainingEffect());
        assertEquals(null, metrics.anaerobicTrainingEffect());
        assertEquals(null, metrics.trainingLoad());
    }

    @Test
    void rejectsInvalidActivityIdWithoutRequestingDetail() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2).activityTrainingMetrics("../123"));

        assertEquals("INVALID_EXTERNAL_ID", failure.code());
    }

    @Test
    void rejectsDetailResponseWithoutSummaryObject() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activity-service/activity/123", exchange -> respond(exchange, 200, "{}"));
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2).activityTrainingMetrics("123"));

        assertEquals("INVALID_RESPONSE", failure.code());
    }

    @Test
    void doesNotFollowRedirectsFromAnAuthorizedSearchRequest() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> { redirected.incrementAndGet(); respond(exchange, 200, "[]"); });
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2)
                .listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("REQUEST_REJECTED", failure.code());
        assertEquals(0, redirected.get());
    }

    @Test
    void appliesTheConfiguredResponseTimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> {
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "[]");
        });
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2)
                .listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("NETWORK_ERROR", failure.code());
    }

    @Test
    void appliesResponseTimeoutToBodyReadsAfterHeadersArrive() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> respond(exchange, 200, "token"));
        server.createContext("/activitylist-service/activities/search/activities", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('[');
                output.flush();
                try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                output.write(']');
            }
        });
        server.start();

        var failure = assertThrows(GarminClientException.class, () -> client(10, 2)
                .listActivities(LocalDate.MIN, LocalDate.MAX));

        assertEquals("NETWORK_ERROR", failure.code());
    }

    private GarminPersonalApiClient client(int pageSize, int maxPages) {
        var properties = new GarminPersonalApiProperties(true, "private-test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMinutes(30), 90, pageSize, maxPages, 1_048_576, Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new GarminPersonalApiClient(properties);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
