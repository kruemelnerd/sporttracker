package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IntervalsIcuClientTests {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void listsAthleteZeroActivitiesWithBasicAuthAndUserAgent() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().toString());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "[{\"id\":\"abc\",\"type\":\"TrailRun\"}]".getBytes(StandardCharsets.UTF_8), "application/json");
        });
        var client = client("secret-test-only");

        var activities = client.listActivities(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-19"));

        assertEquals("/api/v1/athlete/0/activities?oldest=2026-10-05&newest=2026-10-19", path.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("API_KEY:secret-test-only".getBytes(StandardCharsets.UTF_8)), auth.get());
        assertEquals("running-dashboard/1.0", userAgent.get());
        assertEquals("abc", activities.getFirst().id());
        assertEquals("TrailRun", activities.getFirst().type());
    }

    @Test
    void downloadsTheOriginalFileAndPreservesResponseHeadersAndBytes() throws Exception {
        byte[] content = {31, (byte) 139, 8, 0, 1, 2};
        startServer(exchange -> {
            assertEquals("/api/v1/activity/abc/file", exchange.getRequestURI().toString());
            respond(exchange, 200, content, "application/gzip");
        });

        var downloaded = client("key").downloadActivityFile("abc");

        assertArrayEquals(content, downloaded.body());
        assertEquals("application/gzip", downloaded.contentType());
    }

    @Test
    void boundsTheDownloadedResponseBody() throws Exception {
        startServer(exchange -> respond(exchange, 200, new byte[5], "application/gzip"));
        var properties = new IntervalsIcuProperties(true, "test-key", "http://localhost:" + server.getAddress().getPort(), Duration.ofMinutes(15), 90);
        var client = new IntervalsIcuClient(properties, new ImportProperties(10, 4, 4));

        var error = assertThrows(IntervalsIcuClientException.class, () -> client.downloadActivityFile("abc"));

        assertEquals("FILE_TOO_LARGE", error.code());
        assertFalse(error.getMessage().contains("test-key"));
    }

    @Test
    void mapsHttpErrorsToSafeMessagesAndHonorsRetryAfter() throws Exception {
        for (var response : new Object[][] {
                {401, "INVALID_API_KEY"}, {403, "ACCESS_DENIED"}, {404, "FILE_NOT_AVAILABLE"},
                {500, "INTERVALS_ERROR"}
        }) {
            int status = (int) response[0];
            String code = (String) response[1];
            startServer(exchange -> respond(exchange, status, new byte[0], "application/json"));
            var error = assertThrows(IntervalsIcuClientException.class,
                    () -> client("never-in-message").downloadActivityFile("abc"));
            assertEquals(code, error.code());
            assertFalse(error.getMessage().contains("never-in-message"));
            stopServer();
            server = null;
        }

        startServer(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", "120");
            respond(exchange, 429, new byte[0], "application/json");
        });
        var error = assertThrows(IntervalsIcuClientException.class,
                () -> client("never-in-message").listActivities(LocalDate.now(), LocalDate.now()));
        assertEquals("RATE_LIMITED", error.code());
        assertEquals(Duration.ofSeconds(120), error.retryAfter());
        assertFalse(error.getMessage().contains("never-in-message"));
    }

    @Test
    void mapsNetworkFailuresWithoutRevealingTheCredential() throws Exception {
        startServer(exchange -> respond(exchange, 200, new byte[0], "application/json"));
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        var properties = new IntervalsIcuProperties(true, "never-in-message", "http://127.0.0.1:" + port, Duration.ofMinutes(15), 90);

        var error = assertThrows(IntervalsIcuClientException.class,
                () -> new IntervalsIcuClient(properties, new ImportProperties(10, 10_485_760, 104_857_600)).listActivities(LocalDate.now(), LocalDate.now()));

        assertEquals("NETWORK_ERROR", error.code());
        assertFalse(error.getMessage().contains("never-in-message"));
    }

    @Test
    void timesOutWhenTheServerDoesNotRespond() throws Exception {
        startServer(exchange -> {
            try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "[]".getBytes(StandardCharsets.UTF_8), "application/json");
        });
        var properties = new IntervalsIcuProperties(true, "key", "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMinutes(15), 90);
        var client = new IntervalsIcuClient(properties, new ImportProperties(10, 10_485_760, 104_857_600), Duration.ofMillis(100), Duration.ofMillis(30));

        var error = assertThrows(IntervalsIcuClientException.class,
                () -> client.listActivities(LocalDate.now(), LocalDate.now()));

        assertEquals("NETWORK_ERROR", error.code());
    }

    private IntervalsIcuClient client(String key) {
        var properties = new IntervalsIcuProperties(true, key, "http://localhost:" + server.getAddress().getPort(), Duration.ofMinutes(15), 90);
        return new IntervalsIcuClient(properties, new ImportProperties(10, 10_485_760, 104_857_600));
    }

    private void startServer(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body, String contentType) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }
}
