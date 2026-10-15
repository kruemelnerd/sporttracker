package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TrainingPlanJsonTests {
    private static final String NAME = "m6-test-json";
    private static final String DOCUMENT = """
            {"schemaVersion":1,"plan":{"name":"m6-test-json","goal":"Halbmarathon","startDate":"2026-09-21","endDate":"2027-03-14","raceDate":"2027-03-14","targetTime":105,"description":"Vorbereitung","phases":[{"name":"Grundlagen","startDate":"2026-09-21","endDate":"2026-10-18","description":"Basis"}],"sessions":[{"date":"2026-10-20","sport":"RUN","sessionType":"EASY","title":"Locker","targetDistanceKm":8,"targetRpe":3,"instructions":"Nach Gefühl"}]}}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() { jdbc.update("DELETE FROM training_plan WHERE name LIKE 'm6-test-json%'"); }

    @Test
    void importsExportsAndReimportsTheSameVersionedPlanWithoutLosingFields() throws Exception {
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.plan.goal").value("Halbmarathon"))
                .andExpect(jsonPath("$.plan.phases[0].name").value("Grundlagen"))
                .andExpect(jsonPath("$.plan.sessions[0].instructions").value("Nach Gefühl"));
        String id = jdbc.queryForObject("SELECT id::text FROM training_plan WHERE name = ?", String.class, NAME);
        String sessionIdBefore = jdbc.queryForObject("SELECT s.id::text FROM planned_session s JOIN training_plan p ON p.id = s.training_plan_id WHERE p.name = ?", String.class, NAME);

        var exported = mvc.perform(get("/api/training-plans/{id}/export", id))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.plan.sessions[0].targetDistanceKm").value(8))
                .andReturn().getResponse().getContentAsString();
        String replacePayload = exported.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"replaceExisting\":true");
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(replacePayload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replacedExisting").value(true))
                .andExpect(jsonPath("$.plan.phases[0].description").value("Basis"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM training_plan WHERE name = ?", Integer.class, NAME));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM planned_session s JOIN training_plan p ON p.id = s.training_plan_id WHERE p.name = ?", Integer.class, NAME));
        assertEquals(sessionIdBefore, jdbc.queryForObject("SELECT s.id::text FROM planned_session s JOIN training_plan p ON p.id = s.training_plan_id WHERE p.name = ?", String.class, NAME));
    }

    @Test
    void refusesDuplicatesWithoutConfirmationAndRejectsInvalidDocumentsAtomically() throws Exception {
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT)).andExpect(status().isCreated());
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TRAINING_PLAN"));
        String invalid = DOCUMENT.replace("\"schemaVersion\":1", "\"schemaVersion\":2");
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        invalid = DOCUMENT.replace("\"description\":\"Vorbereitung\"", "\"description\":\"Vorbereitung\",\"unknownField\":true").replace(NAME, "m6-test-json-unknown");
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        invalid = DOCUMENT.replace("\"targetRpe\":3", "\"targetRpe\":11").replace(NAME, "m6-test-json-invalid");
        mvc.perform(post("/api/training-plans/import").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM training_plan WHERE name LIKE 'm6-test-json%'", Integer.class));
    }
}
