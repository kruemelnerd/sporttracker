package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class ImportControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void importsOneFitFileThroughTheMultipartApi() throws Exception {
        mockMvc.perform(multipart("/api/imports")
                        .file(new MockMultipartFile("file", "activity.fit", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcomes[0].status").value("IMPORTED"));
    }
}
