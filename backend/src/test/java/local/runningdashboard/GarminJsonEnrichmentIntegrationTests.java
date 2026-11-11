package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-garmin-json-tests",
        "running-dashboard.garmin-personal-api.enabled=true",
        "running-dashboard.garmin-personal-api.api-key=mock-only-key",
        "running-dashboard.garmin-personal-api.page-size=10",
        "running-dashboard.garmin-personal-api.max-pages=1"
})
@AutoConfigureMockMvc
class GarminJsonEnrichmentIntegrationTests {
    private static final String EXTERNAL_ID = "987654321";
    private static final byte[] FIT = fixture();
    private static final String HASH = hash(FIT);
    private static final AtomicInteger DETAIL_REQUESTS = new AtomicInteger();
    private static HttpServer garmin;
    private static byte[] archive;

    static {
        try {
            archive = zip(FIT);
            garmin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            garmin.createContext("/personalapitoken-service/personal-api/personal-api-key/token", exchange -> {
                assertEquals("ApiKey mock-only-key", exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, "opaque-mock-token");
            });
            garmin.createContext("/activitylist-service/activities/search/activities", exchange -> {
                assertEquals("Bearer opaque-mock-token", exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, "[{\"activityId\":987654321,\"activityType\":{\"typeKey\":\"running\"}}]");
            });
            garmin.createContext("/download-service/files/activity/" + EXTERNAL_ID, exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, archive.length);
                try (var output = exchange.getResponseBody()) { output.write(archive); }
            });
            garmin.createContext("/activity-service/activity/" + EXTERNAL_ID, exchange -> {
                DETAIL_REQUESTS.incrementAndGet();
                respond(exchange, 200, """
                        {"summaryDTO":{"trainingEffect":3.7,"anaerobicTrainingEffect":0,"activityTrainingLoad":191.5,
                        "distance":999999,"movingDuration":1,"averageRunCadence":1}}
                        """);
            });
            garmin.start();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void garminBaseUrl(DynamicPropertyRegistry properties) {
        properties.add("running-dashboard.garmin-personal-api.base-url", () -> "http://127.0.0.1:" + garmin.getAddress().getPort());
    }

    @Autowired private GarminSyncService sync;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @MockitoBean private GarminSyncTriggers automaticSync;

    @AfterAll static void stopGarminMock() { if (garmin != null) garmin.stop(0); }

    @BeforeEach void removeOnlyThisSyntheticActivity() {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", HASH);
        jdbc.update("DELETE FROM garmin_sync_state");
        DETAIL_REQUESTS.set(0);
    }

    @AfterEach void cleanOnlyThisSyntheticActivity() {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", HASH);
        jdbc.update("DELETE FROM garmin_sync_state");
    }

    @Test
    void importsFitEnrichesFromMockedGarminAndReturnsBothSourcesWithoutOverwritingFit() throws Exception {
        var result = sync.synchronize();
        assertEquals(1, result.imported());
        assertEquals(0, result.failed());
        Long activityId = jdbc.queryForObject("SELECT activity_id FROM import_file WHERE sha256 = ?", Long.class, HASH);
        assertEquals(1, DETAIL_REQUESTS.get());
        assertEquals(3.7, jdbc.queryForObject("SELECT garmin_training_effect FROM activity WHERE id = ?", Double.class, activityId));
        assertEquals(0.0, jdbc.queryForObject("SELECT garmin_anaerobic_training_effect FROM activity WHERE id = ?", Double.class, activityId));
        assertEquals(191.5, jdbc.queryForObject("SELECT garmin_activity_training_load FROM activity WHERE id = ?", Double.class, activityId));
        assertTrue(jdbc.queryForObject("SELECT distance_meters < 999999 AND moving_seconds > 1 FROM activity WHERE id = ?", Boolean.class, activityId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT garmin_enrichment_complete FROM activity WHERE id = ?", Boolean.class, activityId));

        mvc.perform(get("/api/activities/{id}", activityId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.garminTrainingMetrics.aerobicTrainingEffect").value(3.7))
                .andExpect(jsonPath("$.garminTrainingMetrics.anaerobicTrainingEffect").value(0.0))
                .andExpect(jsonPath("$.garminTrainingMetrics.trainingLoad").value(191.5))
                .andExpect(jsonPath("$.activity.distanceMeters").value(jdbc.queryForObject("SELECT distance_meters FROM activity WHERE id = ?", Double.class, activityId)));
    }

    private static byte[] fixture() {
        try (InputStream input = new ClassPathResource("fit/synthetic-running-2.fit").getInputStream()) { return input.readAllBytes(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static byte[] zip(byte[] fit) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("activity.fit"));
            output.write(fit);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
