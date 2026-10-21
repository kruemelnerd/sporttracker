package local.runningdashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({FrontendController.class, HealthController.class})
class FrontendControllerTests {
    @Autowired private MockMvc mvc;

    @Test
    void forwardsOnlyKnownVueRoutesToTheApplicationEntryPoint() throws Exception {
        for (String route : new String[] {"/", "/fixture-summary", "/import", "/training-plan", "/activities/123"}) {
            mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
        mvc.perform(get("/unknown-page")).andExpect(status().isNotFound());
        mvc.perform(get("/api/not-a-route")).andExpect(status().isNotFound());
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":\"ok\"}"));
        mvc.perform(get("/missing.js")).andExpect(status().isNotFound());
    }
}
