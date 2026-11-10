package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DataManagementSecurityTests {
    @Autowired private MockMvc mvc;

    @Test
    void deletionIsDisabledByDefaultButStatusIsAvailable() throws Exception {
        mvc.perform(get("/api/data-management/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/data-management/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DELETE_ACTIVITIES\"}"))
                .andExpect(status().isNotFound());
    }
}
