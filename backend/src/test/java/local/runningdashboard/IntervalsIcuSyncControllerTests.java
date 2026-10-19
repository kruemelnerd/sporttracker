package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.task.SyncTaskExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@SpringBootTest(properties = "running-dashboard.intervals-icu.api-key=secret-must-not-be-returned")
@AutoConfigureMockMvc
class IntervalsIcuSyncControllerTests {
    @Autowired private MockMvc mockMvc;
    @Autowired private ImportService imports;
    @Autowired private ActivityImportSourceRepository mappings;

    @Test
    void statusReportsDisabledStateWithoutReturningTheConfiguredKey() throws Exception {
        var response = mockMvc.perform(get("/api/imports/intervals-icu/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.lastAttemptAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.lastSuccessfulSyncAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.lastResult").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("secret-must-not-be-returned"));
    }

    @Test
    void manualSyncRejectsAnUnconfiguredSourceWithoutStartingARequest() throws Exception {
        mockMvc.perform(post("/api/imports/intervals-icu/sync"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void manualSyncEndpointStartsTheSameServiceAndReturnsItsSafeResult() throws Exception {
        ActivityImportSource source = new ActivityImportSource() {
            @Override public String provider() { return "INTERVALS_ICU"; }
            @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) { return List.of(); }
            @Override public ActivityFile downloadActivityFile(String externalId) { throw new AssertionError("no activity should be downloaded"); }
        };
        var properties = new IntervalsIcuProperties(true, "secret-must-not-be-returned", "http://localhost:1", Duration.ofMinutes(15), 90);
        var sync = new IntervalsIcuSyncService(source, imports, mappings, properties, Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new IntervalsIcuSyncController(sync, new SyncTaskExecutor())).build();

        var response = mvc.perform(post("/api/imports/intervals-icu/sync"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.lastResult.started").value(true))
                .andExpect(jsonPath("$.lastResult.checked").value(0))
                .andReturn();

        assertNotNull(sync.lastSuccessfulSyncAt());
        assertFalse(response.getResponse().getContentAsString().contains("secret-must-not-be-returned"));
    }
}
