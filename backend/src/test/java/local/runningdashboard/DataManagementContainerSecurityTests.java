package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "running-dashboard.data-management.enabled=true")
@ActiveProfiles("container")
@AutoConfigureMockMvc
class DataManagementContainerSecurityTests {
    @Autowired private MockMvc mvc;

    @Test
    void containerProfileNeverRegistersDeletionRoutesEvenWhenFeatureFlagIsOverridden() throws Exception {
        mvc.perform(get("/api/data-management/status")).andExpect(status().isNotFound());
        mvc.perform(post("/api/data-management/preview")).andExpect(status().isNotFound());
    }
}
