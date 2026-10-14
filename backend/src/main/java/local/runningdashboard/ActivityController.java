package local.runningdashboard;

import java.sql.Timestamp;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ActivityController {
    private final JdbcTemplate jdbc;
    private final GarminFitFixtureAdapter adapter;
    private final Path dataDirectory;

    public ActivityController(JdbcTemplate jdbc, GarminFitFixtureAdapter adapter,
                              @Value("${running-dashboard.data-directory}") String dataDirectory) {
        this.jdbc = jdbc;
        this.adapter = adapter;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
    }

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
        List<ActivityRecord> records = records(id);
        if (records.stream().anyMatch(record -> record.altitudeMeters() == null)) {
            restoreMissingSeries(id, records);
            records = records(id);
        }
        List<Lap> laps = jdbc.query("SELECT lap_number, duration_seconds, distance_meters, average_speed_mps, average_heart_rate, average_cadence, ascent_meters, descent_meters FROM lap WHERE activity_id = ? ORDER BY lap_number", (result, row) -> new Lap(result.getInt("lap_number"), result.getObject("duration_seconds", Double.class), result.getObject("distance_meters", Double.class), result.getObject("average_speed_mps", Double.class), result.getObject("average_heart_rate", Integer.class), result.getObject("average_cadence", Integer.class), result.getObject("ascent_meters", Double.class), result.getObject("descent_meters", Double.class)), id);
        return new ActivityDetail(activity.activity(), activity.movingSeconds(), activity.averageSpeedMetersPerSecond(), activity.maximumHeartRate(), activity.averageCadence(), activity.calories(), records, laps);
    }

    private List<ActivityRecord> records(long activityId) {
        return jdbc.query("SELECT elapsed_seconds, distance_meters, latitude, longitude, altitude_meters, speed_mps, heart_rate, cadence FROM activity_record WHERE activity_id = ? ORDER BY record_index",
                (result, row) -> new ActivityRecord(result.getObject("elapsed_seconds", Double.class), result.getObject("distance_meters", Double.class), result.getObject("latitude", Double.class), result.getObject("longitude", Double.class), result.getObject("altitude_meters", Double.class), result.getObject("speed_mps", Double.class), result.getObject("heart_rate", Integer.class), result.getObject("cadence", Integer.class)), activityId);
    }

    private void restoreMissingSeries(long activityId, List<ActivityRecord> persisted) {
        List<String> paths = jdbc.query("SELECT original_path FROM import_file WHERE activity_id = ? AND original_path IS NOT NULL ORDER BY imported_at DESC LIMIT 1",
                (result, row) -> result.getString(1), activityId);
        if (paths.isEmpty()) return;
        Path original = dataDirectory.resolve(paths.getFirst()).normalize();
        if (!original.startsWith(dataDirectory) || !Files.isRegularFile(original)) return;
        try (InputStream input = Files.newInputStream(original)) {
            List<GarminFitFixtureAdapter.DecodedRecord> decoded = adapter.decode(input).records();
            if (decoded.size() != persisted.size()) return;
            List<Object[]> updates = new java.util.ArrayList<>();
            for (int index = 0; index < decoded.size(); index++) {
                var source = decoded.get(index);
                var stored = persisted.get(index);
                if (!same(source.distanceMeters(), stored.distanceMeters()) || !same(source.elapsedSeconds(), stored.elapsedSeconds())) return;
                if ((stored.altitudeMeters() == null && source.altitudeMeters() != null)
                        || (stored.speedMetersPerSecond() == null && source.speedMetersPerSecond() != null)) {
                    updates.add(new Object[] { source.altitudeMeters(), source.speedMetersPerSecond(), activityId, index });
                }
            }
            if (!updates.isEmpty()) jdbc.batchUpdate("UPDATE activity_record SET altitude_meters = COALESCE(altitude_meters, ?), speed_mps = COALESCE(speed_mps, ?) WHERE activity_id = ? AND record_index = ?", updates);
        } catch (IOException ignored) {
            // Original FIT is optional; detail metrics remain available from persisted data.
        }
    }

    private static boolean same(Double left, Double right) {
        return left == null ? right == null : right != null && Math.abs(left - right) < 0.001;
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
