package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-import-tests")
@AutoConfigureMockMvc
@ImportAutoConfiguration(JdbcTemplateAutoConfiguration.class)
class ImportControllerTests {
    private static final String FIXTURE_HASH = "d54a2ed4389fdf07955b5c92d166860c5c9138ba9dceea9ccfa623667a834180";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GarminFitFixtureAdapter adapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void removeFixtureAggregate() {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", FIXTURE_HASH);
    }

    @Test
    void importsOneFitFileWithCanonicalPersistedValues() throws Exception {
        removeFixtureAggregate();
        byte[] bytes;
        try (InputStream input = new ClassPathResource("fit/synthetic-running.fit").getInputStream()) {
            bytes = input.readAllBytes();
        }
        assertEquals(FIXTURE_HASH, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));

        mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "activity.fit", "application/octet-stream", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("IMPORTED"))
                .andExpect(jsonPath("$.outcomes[0].activityId").isNumber())
                .andExpect(jsonPath("$.outcomes[0].originalRetained").value(true));

        Long activityId = jdbc.queryForObject("SELECT activity_id FROM import_file WHERE sha256 = ?", Long.class, FIXTURE_HASH);
        assertEquals(Instant.parse("2024-01-01T12:00:00Z"), jdbc.queryForObject("SELECT start_time FROM activity WHERE id = ?", Timestamp.class, activityId).toInstant());
        assertEquals(5000d, jdbc.queryForObject("SELECT distance_meters FROM activity WHERE id = ?", Double.class, activityId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM activity_record WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM lap WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(140, jdbc.queryForObject("SELECT heart_rate FROM activity_record WHERE activity_id = ? AND record_index = 0", Integer.class, activityId));
        assertEquals(10d, jdbc.queryForObject("SELECT altitude_meters FROM activity_record WHERE activity_id = ? AND record_index = 0", Double.class, activityId));
        assertTrue(jdbc.queryForObject("SELECT duration_seconds > 0 AND distance_meters > 0 FROM lap WHERE activity_id = ? AND lap_number = 1", Boolean.class, activityId));
        String originalPath = jdbc.queryForObject("SELECT original_path FROM import_file WHERE activity_id = ?", String.class, activityId);
        assertTrue(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-import-tests", originalPath)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND data_type = 'bytea'", Integer.class));

        mockMvc.perform(get("/api/activities").param("from", "2024-01-01T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(activityId))
                .andExpect(jsonPath("$[0].sport").value("running"))
                .andExpect(jsonPath("$[0].distanceMeters").value(5000d));
    }

    @Test
    void preservesSemicirclePrecisionAndDropsInvalidLatitudeWithoutDroppingTheRecord() {
        assertEquals(10.34802851267159d, GarminFitFixtureAdapter.degrees(123456789, true));
        assertNull(GarminFitFixtureAdapter.degrees(1073741825, true));
        assertEquals(0d, GarminFitFixtureAdapter.degrees(0, true));
    }

    @Test
    void returnsIndependentOutcomesForAnImportedAndMalformedFile() throws Exception {
        removeFixtureAggregate();

        mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("files", "activity.fit", "application/octet-stream", fixtureBytes()))
                        .file(new MockMultipartFile("files", "broken.fit", "application/octet-stream", new byte[] {1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("IMPORTED"))
                .andExpect(jsonPath("$.outcomes[1].status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is("INVALID"), org.hamcrest.Matchers.is("FAILED"))));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", Integer.class, FIXTURE_HASH));
    }

    @Test
    void classifiesAnExactRetryAsDuplicate() throws Exception {
        removeFixtureAggregate();
        byte[] bytes = fixtureBytes();

        mockMvc.perform(multipart("/api/imports").file(new MockMultipartFile("files", "activity.fit", "application/octet-stream", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("IMPORTED"));
        mockMvc.perform(multipart("/api/imports").file(new MockMultipartFile("files", "activity.fit", "application/octet-stream", bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("DUPLICATE"));
    }

    @Test
    void classifiesAChangedByteReexportWithTheSameSessionAsDuplicate() throws Exception {
        removeFixtureAggregate();
        byte[] original = fixtureBytes();
        byte[] reexport = Arrays.copyOf(original, original.length + 1);
        GarminFitFixtureAdapter.DecodedActivity decoded = adapter.decode(new ByteArrayInputStream(original));
        ImportService imports = new ImportService(new GarminFitFixtureAdapter() {
            @Override public DecodedActivity decode(InputStream ignored) { return decoded; }
        }, jdbc, new TransactionTemplate(transactionManager), Path.of(System.getProperty("java.io.tmpdir"), "running-dashboard-import-tests").toString(), new ImportProperties(10, 10_485_760, 104_857_600));

        assertEquals("IMPORTED", imports.importFiles(List.of(new MockMultipartFile("files", "activity.fit", "application/octet-stream", original))).outcomes().getFirst().status());
        assertEquals("DUPLICATE", imports.importFiles(List.of(new MockMultipartFile("files", "reexport.fit", "application/octet-stream", reexport))).outcomes().getFirst().status());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM activity WHERE sport = 'running' AND start_time = ?", Integer.class, Timestamp.from(Instant.parse("2024-01-01T12:00:00Z"))));
    }

    @Test
    void rejectsAnOversizedBatchBeforeDecodingAnyFile() throws Exception {
        var request = multipart("/api/imports");
        for (int index = 0; index < 11; index++) {
            request.file(new MockMultipartFile("files", "activity-" + index + ".fit", "application/octet-stream", new byte[] {1}));
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes").isEmpty())
                .andExpect(jsonPath("$.batchError.code").value("BATCH_COUNT_EXCEEDED"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM import_file WHERE sha256 <> ?", Integer.class, FIXTURE_HASH));
    }

    @Test
    void rejectsAnEmptyFileWithoutPersistingAnything() throws Exception {
        mockMvc.perform(multipart("/api/imports").file(new MockMultipartFile("files", "empty.fit", "application/octet-stream", new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("INVALID"))
                .andExpect(jsonPath("$.outcomes[0].code").value("EMPTY_FILE"));
    }

    private byte[] fixtureBytes() throws Exception {
        try (InputStream input = new ClassPathResource("fit/synthetic-running.fit").getInputStream()) {
            return input.readAllBytes();
        }
    }
}
