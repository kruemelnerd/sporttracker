package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@SpringBootTest
class GarminSyncControllerTests {
    @Autowired private ImportService imports;
    @Autowired private ActivityImportSourceRepository mappings;
    @Autowired private GarminSyncStateRepository states;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void disabledStatusDoesNotClaimAConnectionOrReturnCredentials() throws Exception {
        var props = props(false, "synthetic-private-key");
        var service = service(props, new EmptySource());
        var mvc = MockMvcBuilders.standaloneSetup(new GarminSyncController(service, props,
                new IntervalsIcuProperties(false, "", "https://intervals.icu", Duration.ofMinutes(15), 90),
                new SyncTaskExecutor())).build();

        var response = mvc.perform(get("/api/imports/garmin/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.apiKeyPresent").value(true)).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.connected").value(false)).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("synthetic-private-key"));
    }

    @Test
    void statusWithoutAnApiKeyIsNotConfigured() throws Exception {
        var props = props(false, "");
        var mvc = MockMvcBuilders.standaloneSetup(new GarminSyncController(service(props, new EmptySource()), props,
                new IntervalsIcuProperties(false, "", "https://intervals.icu", Duration.ofMinutes(15), 90),
                new SyncTaskExecutor())).build();

        mvc.perform(get("/api/imports/garmin/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apiKeyPresent").value(false))
                .andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void manualSyncUsesTheServiceAndReturnsOnlyAggregateStatus() throws Exception {
        jdbc.update("DELETE FROM garmin_sync_state");
        var props = props(true, "synthetic-private-key");
        var service = service(props, new EmptySource());
        var mvc = MockMvcBuilders.standaloneSetup(new GarminSyncController(service, props,
                new IntervalsIcuProperties(false, "", "https://intervals.icu", Duration.ofMinutes(15), 90),
                new SyncTaskExecutor())).build();

        var response = mvc.perform(post("/api/imports/garmin/sync"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.lastResult.checked").value(0)).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains("synthetic-private-key"));
        jdbc.update("DELETE FROM garmin_sync_state");
    }

    private GarminSyncService service(GarminPersonalApiProperties properties, ActivityImportSource source) {
        return new GarminSyncService(source, imports, mappings, properties, states, java.time.Clock.systemUTC(), java.time.ZoneOffset.UTC);
    }

    private static GarminPersonalApiProperties props(boolean enabled, String key) {
        return new GarminPersonalApiProperties(enabled, key, "https://personal.connectapi.garmin.com",
                Duration.ofMinutes(30), 90, 50, 20, 52_428_800, Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    private static final class EmptySource implements ActivityImportSource {
        @Override public String provider() { return "GARMIN"; }
        @Override public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) { return List.of(); }
        @Override public ActivityFile downloadActivityFile(String externalId) { throw new AssertionError("No activity should be downloaded"); }
    }
}
