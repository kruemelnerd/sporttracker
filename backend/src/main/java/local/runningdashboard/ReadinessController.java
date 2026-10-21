package local.runningdashboard;

import java.sql.Statement;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {
    private final JdbcTemplate jdbcTemplate;

    public ReadinessController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/ready")
    public Map<String, String> ready() {
        try {
            Boolean available = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(2);
                    try (var resultSet = statement.executeQuery("SELECT 1")) {
                        return resultSet.next() && resultSet.getInt(1) == 1;
                    }
                }
            });
            if (!Boolean.TRUE.equals(available)) throw new IllegalStateException("Readiness query failed");
            return Map.of("status", "ready");
        } catch (DataAccessException | IllegalStateException failure) {
            throw new ReadinessUnavailableException();
        }
    }

    @ExceptionHandler(ReadinessUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "not-ready"));
    }

    static class ReadinessUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
