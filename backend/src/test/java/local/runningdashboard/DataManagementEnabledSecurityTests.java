package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "running-dashboard.data-management.enabled=true")
@AutoConfigureMockMvc
class DataManagementEnabledSecurityTests {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void blocksCrossSiteMissingOriginAndNonJsonPosts() throws Exception {
        mvc.perform(post("/api/data-management/preview").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "https://attacker.invalid").header("Sec-Fetch-Site", "cross-site")
                        .content("{\"action\":\"DELETE_ACTIVITIES\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/data-management/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DELETE_ACTIVITIES\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/data-management/preview").contentType(MediaType.TEXT_PLAIN)
                        .header("Origin", "http://localhost").content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void sameOriginViteProxyRequestUsesBrowserHostAndDoesNotCachePreviewToken() throws Exception {
        mvc.perform(post("/api/data-management/preview").contentType(MediaType.APPLICATION_JSON)
                        .header("Host", "localhost:5173").header("Origin", "http://localhost:5173")
                        .content("{\"action\":\"DELETE_ACTIVITIES\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals("no-store", result.getResponse().getHeader("Cache-Control")));
    }

    @Test
    void sameOriginPreviewRequiresExplicitConfirmationAndResumeIsExplicit() throws Exception {
        var preview = mvc.perform(post("/api/data-management/preview").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost").content("{\"action\":\"DELETE_ACTIVITIES\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        String token = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(preview.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/data-management/confirm").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost")
                        .content("{\"action\":\"DELETE_ACTIVITIES\",\"token\":\"" + token + "\",\"phrase\":\"wrong\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/data-management/sync/GARMIN/resume").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost").content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/data-management/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @Transactional
    void rejectsStalePreviewAndDoesNotEnableCrossOriginAccess() throws Exception {
        var preview = mvc.perform(post("/api/data-management/preview").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost").content("{\"action\":\"DELETE_ACTIVITIES\"}"))
                .andExpect(status().isOk()).andReturn();
        String token = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(preview.getResponse().getContentAsString()).get("token").asText();
        jdbc.update("INSERT INTO activity (sport, start_time) VALUES ('running', CURRENT_TIMESTAMP)");
        mvc.perform(post("/api/data-management/confirm").contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", "http://localhost")
                        .content("{\"action\":\"DELETE_ACTIVITIES\",\"token\":\"" + token + "\",\"phrase\":\"ALLE AKTIVITÄTEN LÖSCHEN\"}"))
                .andExpect(status().isConflict());
        mvc.perform(options("/api/data-management/preview").header("Origin", "https://attacker.invalid")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNull(result.getResponse().getHeader("Access-Control-Allow-Origin")));
    }
}
