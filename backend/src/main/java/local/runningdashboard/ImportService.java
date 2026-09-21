package local.runningdashboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImportService {
    private final GarminFitFixtureAdapter adapter;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Path dataDirectory;

    public ImportService(GarminFitFixtureAdapter adapter, JdbcTemplate jdbc, TransactionTemplate transactions,
                         @Value("${running-dashboard.data-directory}") String dataDirectory) {
        this.adapter = adapter;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
    }

    public ImportOutcome importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return ImportOutcome.invalid("EMPTY_FILE");
        try {
            byte[] bytes = file.getBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM import_file WHERE sha256 = ?)", Boolean.class, hash))) {
                return ImportOutcome.duplicate();
            }
            GarminFitFixtureAdapter.DecodedActivity decoded = adapter.decode(new java.io.ByteArrayInputStream(bytes));
            if (!"running".equals(decoded.sport())) return ImportOutcome.skipped();
            Long activityId = transactions.execute(status -> persist(decoded, hash, file.getSize()));
            if (activityId == null) return ImportOutcome.failed();
            boolean retained = retainOriginal(hash, bytes, activityId);
            return ImportOutcome.imported(activityId, retained);
        } catch (IOException exception) {
            return ImportOutcome.invalid("INVALID_FIT");
        } catch (Exception exception) {
            return ImportOutcome.failed();
        }
    }

    private Long persist(GarminFitFixtureAdapter.DecodedActivity decoded, String hash, long sizeBytes) {
        Long activityId = jdbc.queryForObject("INSERT INTO activity (sport, sub_sport, start_time, elapsed_seconds, moving_seconds, distance_meters, average_speed_mps, average_heart_rate, average_cadence, ascent_meters, calories) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, decoded.sport(), decoded.subSport(), decoded.startTime(), decoded.elapsedSeconds(), decoded.movingSeconds(), decoded.distanceMeters(), decoded.averageSpeedMetersPerSecond(), decoded.averageHeartRate(), decoded.averageCadence(), decoded.ascentMeters(), decoded.calories());
        jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes) VALUES (?, ?, ?)", activityId, hash, sizeBytes);
        int index = 0;
        for (GarminFitFixtureAdapter.DecodedRecord record : decoded.records()) {
            jdbc.update("INSERT INTO activity_record (activity_id, record_index, record_time, elapsed_seconds, distance_meters, latitude, longitude, altitude_meters, speed_mps, heart_rate, cadence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, index++, record.timestamp(), record.elapsedSeconds(), record.distanceMeters(), record.latitude(), record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(), record.heartRate(), record.cadence());
        }
        for (GarminFitFixtureAdapter.DecodedLap lap : decoded.laps()) {
            jdbc.update("INSERT INTO lap (activity_id, lap_number, duration_seconds, distance_meters, average_speed_mps, average_heart_rate, average_cadence, ascent_meters) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, lap.lapNumber(), lap.durationSeconds(), lap.distanceMeters(), lap.averageSpeedMetersPerSecond(), lap.averageHeartRate(), lap.averageCadence(), lap.ascentMeters());
        }
        return activityId;
    }

    private boolean retainOriginal(String hash, byte[] bytes, long activityId) {
        try {
            Path target = dataDirectory.resolve("originals").resolve(hash.substring(0, 2)).resolve(hash + ".fit").normalize();
            if (!target.startsWith(dataDirectory)) throw new IOException("invalid data path");
            Files.createDirectories(target.getParent());
            if (!Files.exists(target)) Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            jdbc.update("UPDATE import_file SET original_path = ? WHERE activity_id = ?", dataDirectory.relativize(target).toString(), activityId);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    public record ImportOutcome(String status, Long activityId, boolean originalRetained, String code) {
        static ImportOutcome imported(long activityId, boolean retained) { return new ImportOutcome("IMPORTED", activityId, retained, null); }
        static ImportOutcome duplicate() { return new ImportOutcome("DUPLICATE", null, false, null); }
        static ImportOutcome skipped() { return new ImportOutcome("SKIPPED", null, false, "NON_RUNNING"); }
        static ImportOutcome invalid(String code) { return new ImportOutcome("INVALID", null, false, code); }
        static ImportOutcome failed() { return new ImportOutcome("FAILED", null, false, "IMPORT_FAILED"); }
    }
}
