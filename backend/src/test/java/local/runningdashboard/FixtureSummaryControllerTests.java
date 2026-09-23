package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;

@WebMvcTest(FixtureSummaryController.class)
@Import(GarminFitFixtureAdapter.class)
class FixtureSummaryControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsTypedUtcFixtureSummary() throws Exception {
        mockMvc.perform(get("/api/fixture-summary"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.sport").value("running"))
                .andExpect(jsonPath("$.startTime").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.distanceMeters").isNumber())
                .andExpect(jsonPath("$.status").value("decoded"))
                .andExpect(jsonPath("$.recordCount").isNumber())
                .andExpect(jsonPath("$.lapCount").isNumber());
    }
}
