package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class TrainingPlanControllerTests {
    private static final String NAME = "m6-test-api";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM training_plan WHERE name LIKE 'm6-test-%'");
    }

    @Test
    void createsPlanPhasesAndSessionThenMovesAndDeletesSession() throws Exception {
        String planBody = """
                {"name":"m6-test-api","goal":"Halbmarathon","startDate":"2026-09-21","endDate":"2027-03-14","raceDate":"2027-03-14","targetTime":105,"description":"lokal"}
                """;
        mvc.perform(post("/api/training-plans").contentType(MediaType.APPLICATION_JSON).content(planBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.startDate").value("2026-09-21"));
        String planId = jdbc.queryForObject("SELECT id::text FROM training_plan WHERE name = ?", String.class, NAME);
        mvc.perform(get("/api/training-plans")).andExpect(status().isOk()).andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.hasItem(planId)));

        mvc.perform(put("/api/training-plans/{id}/phases", planId).contentType(MediaType.APPLICATION_JSON).content("""
                [{"name":"Grundlage","startDate":"2026-09-21","endDate":"2026-10-18","description":"Basis"}]
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Grundlage"));

        mvc.perform(post("/api/training-plans/{id}/sessions", planId).contentType(MediaType.APPLICATION_JSON).content("""
                {"date":"2026-10-20","sport":"RUN","sessionType":"EASY","title":"Locker","targetDistanceKm":8,"targetRpe":3}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.date").value("2026-10-20"));
        String sessionId = jdbc.queryForObject("SELECT id::text FROM planned_session WHERE training_plan_id = ?", String.class, java.util.UUID.fromString(planId));
        mvc.perform(get("/api/training-plans/{id}/sessions?from=2026-10-19&to=2026-10-21", planId))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Locker"));

        mvc.perform(put("/api/training-plans/{id}/sessions/{sessionId}", planId, sessionId).contentType(MediaType.APPLICATION_JSON).content("""
                {"date":"2026-10-21","sport":"RUN","sessionType":"EASY","title":"Verschoben","targetDistanceKm":8,"targetRpe":3}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-10-21"));
        mvc.perform(delete("/api/training-plans/{id}/sessions/{sessionId}", planId, sessionId)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM planned_session WHERE training_plan_id = ?", Integer.class, java.util.UUID.fromString(planId)));

        mvc.perform(put("/api/training-plans/{id}", planId).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"m6-test-edited","goal":"Halbmarathon","startDate":"2026-09-21","endDate":"2027-03-14","raceDate":"2027-03-14","targetTime":103}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.targetTime").value(103));
        mvc.perform(delete("/api/training-plans/{id}", planId)).andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM training_phase WHERE training_plan_id = ?", Integer.class, java.util.UUID.fromString(planId)));
    }

    @Test
    void rejectsInvalidPlanAndSessionValuesWithoutPersistingThem() throws Exception {
        mvc.perform(post("/api/training-plans").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":" ","goal":"Race","startDate":"2027-03-15","endDate":"2027-03-14"}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());

        mvc.perform(post("/api/training-plans").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"m6-test-invalid","goal":"Race","startDate":"2027-03-01","endDate":"2027-03-20"}
                """))
                .andExpect(status().isCreated());
        String planId = jdbc.queryForObject("SELECT id::text FROM training_plan WHERE name = ?", String.class, "m6-test-invalid");
        mvc.perform(post("/api/training-plans/{id}/sessions", planId).contentType(MediaType.APPLICATION_JSON).content("""
                {"date":"2027-03-15","sport":"RUN","sessionType":"EASY","title":"Ungültig","targetRpe":11,"targetPaceFrom":6,"targetPaceTo":5}
                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM planned_session WHERE training_plan_id = ?", Integer.class, java.util.UUID.fromString(planId)));
    }
}
