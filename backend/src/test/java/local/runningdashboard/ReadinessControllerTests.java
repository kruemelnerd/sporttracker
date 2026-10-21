package local.runningdashboard;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReadinessControllerTests {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReadinessController(jdbcTemplate)).build();

    @Test
    void readinessReturnsOkAfterReadOnlyDatabaseQuery() throws Exception {
        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ConnectionCallback<Boolean>>any()))
                .thenReturn(true);

        mvc.perform(get("/api/ready")).andExpect(status().isOk()).andExpect(content().json("{\"status\":\"ready\"}"));

        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ConnectionCallback<Boolean>>any());
    }

    @Test
    void readinessHidesDatabaseFailureDetails() throws Exception {
        when(jdbcTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.ConnectionCallback<Boolean>>any()))
                .thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("private connection detail"));

        mvc.perform(get("/api/ready")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"status\":\"not-ready\"}"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private connection detail"))));
    }
}
