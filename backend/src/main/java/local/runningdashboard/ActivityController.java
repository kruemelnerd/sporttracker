package local.runningdashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

    @GetMapping("/api/activities/{id}")
    ActivityDetail activity(@org.springframework.web.bind.annotation.PathVariable long id) {
        List<ActivityDetail> activities = jdbc.query(
                "SELECT id, sport, sub_sport, start_time, elapsed_seconds, moving_seconds, distance_meters, average_speed_mps, average_heart_rate, maximum_heart_rate, average_cadence, ascent_meters, calories FROM activity WHERE id = ?",
                (result, row) -> new ActivityDetail(new ActivitySummary(result.getLong("id"), result.getString("sport"), result.getString("sub_sport"), result.getTimestamp("start_time").toInstant(), result.getObject("elapsed_seconds", Double.class), result.getObject("distance_meters", Double.class), result.getObject("average_heart_rate", Integer.class), result.getObject("ascent_meters", Double.class)), result.getObject("moving_seconds", Double.class), result.getObject("average_speed_mps", Double.class), result.getObject("maximum_heart_rate", Integer.class), result.getObject("average_cadence", Integer.class), result.getObject("calories", Integer.class)), id);
        if (activities.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        ActivityDetail activity = activities.getFirst();
        List<ActivityRecord> records = jdbc.query("SELECT elapsed_seconds, distance_meters, latitude, longitude, altitude_meters, speed_mps, heart_rate, cadence FROM activity_record WHERE activity_id = ? ORDER BY record_index", (result, row) -> new ActivityRecord(result.getObject("elapsed_seconds", Double.class), result.getObject("distance_meters", Double.class), result.getObject("latitude", Double.class), result.getObject("longitude", Double.class), result.getObject("altitude_meters", Double.class), result.getObject("speed_mps", Double.class), result.getObject("heart_rate", Integer.class), result.getObject("cadence", Integer.class)), id);
        List<Lap> laps = jdbc.query("SELECT lap_number, duration_seconds, distance_meters, average_speed_mps, average_heart_rate, average_cadence, ascent_meters, descent_meters FROM lap WHERE activity_id = ? ORDER BY lap_number", (result, row) -> new Lap(result.getInt("lap_number"), result.getObject("duration_seconds", Double.class), result.getObject("distance_meters", Double.class), result.getObject("average_speed_mps", Double.class), result.getObject("average_heart_rate", Integer.class), result.getObject("average_cadence", Integer.class), result.getObject("ascent_meters", Double.class), result.getObject("descent_meters", Double.class)), id);
        return new ActivityDetail(activity.activity(), activity.movingSeconds(), activity.averageSpeedMetersPerSecond(), activity.maximumHeartRate(), activity.averageCadence(), activity.calories(), records, laps);
    }

    public record ActivitySummary(long id, String sport, String subSport, Instant startTime, Double elapsedSeconds,
                                  Double distanceMeters, Integer averageHeartRate, Double ascentMeters) { }
    public record ActivityRecord(Double elapsedSeconds, Double distanceMeters, Double latitude, Double longitude,
                                 Double altitudeMeters, Double speedMetersPerSecond, Integer heartRate, Integer cadence) { }
    public record Lap(int lapNumber, Double durationSeconds, Double distanceMeters, Double averageSpeedMetersPerSecond,
                      Integer averageHeartRate, Integer averageCadence, Double ascentMeters, Double descentMeters) { }
    public record ActivityDetail(ActivitySummary activity, Double movingSeconds, Double averageSpeedMetersPerSecond,
                                 Integer maximumHeartRate, Integer averageCadence, Integer calories,
                                 List<ActivityRecord> records, List<Lap> laps) {
        ActivityDetail(ActivitySummary activity, Double movingSeconds, Double averageSpeedMetersPerSecond,
                       Integer maximumHeartRate, Integer averageCadence, Integer calories) {
            this(activity, movingSeconds, averageSpeedMetersPerSecond, maximumHeartRate, averageCadence, calories, List.of(), List.of());
        }
    }
}
