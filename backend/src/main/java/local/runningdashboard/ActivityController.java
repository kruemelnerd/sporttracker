package local.runningdashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {
    private final JdbcTemplate jdbc;

    public ActivityController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/activities")
    List<ActivitySummary> activities(@RequestParam Instant from) {
        return jdbc.query(
                "SELECT id, sport, sub_sport, start_time, elapsed_seconds, distance_meters, average_heart_rate, ascent_meters FROM activity WHERE sport = 'running' AND start_time >= ? AND start_time <= CURRENT_TIMESTAMP ORDER BY start_time DESC",
                (result, row) -> new ActivitySummary(result.getLong("id"), result.getString("sport"), result.getString("sub_sport"),
                        result.getTimestamp("start_time").toInstant(), result.getObject("elapsed_seconds", Double.class),
                        result.getObject("distance_meters", Double.class), result.getObject("average_heart_rate", Integer.class),
                        result.getObject("ascent_meters", Double.class)), Timestamp.from(from));
    }

    public record ActivitySummary(long id, String sport, String subSport, Instant startTime, Double elapsedSeconds,
                                  Double distanceMeters, Integer averageHeartRate, Double ascentMeters) { }
}
