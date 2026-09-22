package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "running-dashboard.data-directory=${java.io.tmpdir}/running-dashboard-import-tests")
@AutoConfigureMockMvc
class ImportControllerTests {
    private static final String FIXTURE_HASH = "fe0710d14f164f033948d3f499ee72886fbf1f11c5193540459cbd37a1d39a67";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeFixtureAggregate() {
        jdbc.update("DELETE FROM activity WHERE id IN (SELECT activity_id FROM import_file WHERE sha256 = ?)", FIXTURE_HASH);
    }

    @Test
    void importsOneFitFileWithCanonicalPersistedValues() throws Exception {
        removeFixtureAggregate();
        byte[] bytes;
        try (InputStream input = new ClassPathResource("fit/20053968686_ACTIVITY.fit").getInputStream()) {
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
        assertEquals(Instant.parse("2025-08-14T06:03:00Z"), jdbc.queryForObject("SELECT start_time FROM activity WHERE id = ?", Timestamp.class, activityId).toInstant());
        assertEquals(10137.0498046875d, jdbc.queryForObject("SELECT distance_meters FROM activity WHERE id = ?", Double.class, activityId));
        assertEquals(3578, jdbc.queryForObject("SELECT count(*) FROM activity_record WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM lap WHERE activity_id = ?", Integer.class, activityId));
        assertEquals(0, jdbc.queryForObject("SELECT cadence FROM activity_record WHERE activity_id = ? AND record_index = 0", Integer.class, activityId));
        assertNull(jdbc.queryForObject("SELECT altitude_meters FROM activity_record WHERE activity_id = ? AND record_index = 0", Double.class, activityId));
        assertEquals(51.78287987597287d, jdbc.queryForObject("SELECT latitude FROM activity_record WHERE activity_id = ? AND record_index = 0", Double.class, activityId));
        assertTrue(jdbc.queryForObject("SELECT duration_seconds > 0 AND distance_meters > 0 FROM lap WHERE activity_id = ? AND lap_number = 1", Boolean.class, activityId));
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
        try (InputStream input = new ClassPathResource("fit/20053968686_ACTIVITY.fit").getInputStream()) {
            return input.readAllBytes();
        }
    }
}
