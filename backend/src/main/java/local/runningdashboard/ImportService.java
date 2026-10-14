package local.runningdashboard;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(prefix = "running-dashboard.import", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ImportService {
    private final GarminFitFixtureAdapter adapter;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Path dataDirectory;
    private final ImportProperties properties;

    public ImportService(GarminFitFixtureAdapter adapter, JdbcTemplate jdbc, TransactionTemplate transactions,
                          @Value("${running-dashboard.data-directory}") String dataDirectory, ImportProperties properties) {
        this.adapter = adapter;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.properties = properties;
    }

    public ImportBatch importFiles(List<MultipartFile> files) {
        if (files == null || files.size() > properties.maxBatchCount()) {
            return new ImportBatch(List.of(), new ImportBatchError("BATCH_COUNT_EXCEEDED", "Select at most " + properties.maxBatchCount() + " files per import."));
        }
        List<ImportOutcome> outcomes = new ArrayList<>(files.size());
        for (MultipartFile file : files) outcomes.add(importFile(file));
        return new ImportBatch(List.copyOf(outcomes), null);
    }

    private ImportOutcome importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return ImportOutcome.invalid("EMPTY_FILE");
        if (file.getSize() > properties.maxFileBytes()) return ImportOutcome.invalid("FILE_TOO_LARGE");
        StagedFile staged = null;
        try {
            staged = stage(file);
            String hash = staged.hash();
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM import_file WHERE sha256 = ?)", Boolean.class, hash))) {
                return ImportOutcome.duplicate();
            }
            GarminFitFixtureAdapter.DecodedActivity decoded;
            try (InputStream input = Files.newInputStream(staged.path())) {
                decoded = adapter.decode(input);
            }
            if (!"running".equals(decoded.sport())) return ImportOutcome.skipped();
            Long existing = existingActivity(decoded);
            if (existing != null) {
                jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes) VALUES (?, ?, ?)", existing, hash, staged.size());
                return ImportOutcome.duplicate();
            }
            StagedFile committedStaged = staged;
            Long activityId = transactions.execute(status -> persist(decoded, hash, committedStaged.size()));
            if (activityId == null) return ImportOutcome.failed();
            boolean retained = retainOriginal(hash, staged.path(), activityId);
            staged = null;
            return ImportOutcome.imported(activityId, retained);
        } catch (IOException exception) {
            return ImportOutcome.invalid(exception.getMessage() != null && exception.getMessage().contains("exactly one session") ? "MULTIPLE_ACTIVITIES" : "INVALID_FIT");
        } catch (DataIntegrityViolationException exception) {
            return ImportOutcome.duplicate();
        } catch (Exception exception) {
            return ImportOutcome.failed();
        } finally {
            if (staged != null) deleteQuietly(staged.path());
        }
    }

    private StagedFile stage(MultipartFile file) throws Exception {
        Path temporaryDirectory = dataDirectory.resolve(".incoming").normalize();
        if (!temporaryDirectory.startsWith(dataDirectory)) throw new IOException("invalid temporary path");
        Files.createDirectories(temporaryDirectory);
        Path path = Files.createTempFile(temporaryDirectory, "fit-", ".tmp");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (InputStream input = file.getInputStream(); var output = Files.newOutputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                size += read;
                if (size > properties.maxFileBytes()) throw new IOException("file exceeds configured limit");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (Exception exception) {
            deleteQuietly(path);
            throw exception;
        }
        return new StagedFile(path, HexFormat.of().formatHex(digest.digest()), size);
    }

    private Long existingActivity(GarminFitFixtureAdapter.DecodedActivity decoded) {
        List<ActivityIdentity> candidates = jdbc.query(
                "SELECT id, elapsed_seconds, distance_meters FROM activity WHERE sport = ? AND start_time = ?",
                (result, row) -> new ActivityIdentity(result.getLong("id"), (Double) result.getObject("elapsed_seconds"), (Double) result.getObject("distance_meters")),
                decoded.sport(), Timestamp.from(decoded.startTime()));
        return candidates.stream()
                .filter(candidate -> sameMeasurement(candidate.elapsedSeconds(), decoded.elapsedSeconds(), 2d)
                        && sameMeasurement(candidate.distanceMeters(), decoded.distanceMeters(), 5d))
                .map(ActivityIdentity::id)
                .findFirst()
                .orElse(null);
    }

    private static boolean sameMeasurement(Double left, Double right, double tolerance) {
        return left != null && right != null && Math.abs(left - right) <= tolerance;
    }

    private Long persist(GarminFitFixtureAdapter.DecodedActivity decoded, String hash, long sizeBytes) {
        Long activityId = jdbc.queryForObject("INSERT INTO activity (sport, sub_sport, start_time, elapsed_seconds, moving_seconds, distance_meters, average_speed_mps, average_heart_rate, maximum_heart_rate, average_cadence, ascent_meters, calories) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, decoded.sport(), decoded.subSport(), Timestamp.from(decoded.startTime()), decoded.elapsedSeconds(), decoded.movingSeconds(), decoded.distanceMeters(), decoded.averageSpeedMetersPerSecond(), decoded.averageHeartRate(), decoded.maximumHeartRate(), decoded.averageCadence(), decoded.ascentMeters(), decoded.calories());
        jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes) VALUES (?, ?, ?)", activityId, hash, sizeBytes);
        int index = 0;
        for (GarminFitFixtureAdapter.DecodedRecord record : decoded.records()) {
            jdbc.update("INSERT INTO activity_record (activity_id, record_index, record_time, elapsed_seconds, distance_meters, latitude, longitude, altitude_meters, speed_mps, heart_rate, cadence, descent_meters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, index++, record.timestamp() == null ? null : Timestamp.from(record.timestamp()), record.elapsedSeconds(), record.distanceMeters(), record.latitude(), record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(), record.heartRate(), record.cadence(), record.descentMeters());
        }
        for (GarminFitFixtureAdapter.DecodedLap lap : decoded.laps()) {
            jdbc.update("INSERT INTO lap (activity_id, lap_number, duration_seconds, distance_meters, average_speed_mps, average_heart_rate, average_cadence, ascent_meters, descent_meters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, lap.lapNumber(), lap.durationSeconds(), lap.distanceMeters(), lap.averageSpeedMetersPerSecond(), lap.averageHeartRate(), lap.averageCadence(), lap.ascentMeters(), lap.descentMeters());
        }
        return activityId;
    }

    private boolean retainOriginal(String hash, Path temporary, long activityId) {
        try {
            Path target = dataDirectory.resolve("originals").resolve(hash.substring(0, 2)).resolve(hash + ".fit").normalize();
            if (!target.startsWith(dataDirectory)) throw new IOException("invalid data path");
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) deleteQuietly(temporary);
            else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            jdbc.update("UPDATE import_file SET original_path = ? WHERE activity_id = ?", dataDirectory.relativize(target).toString(), activityId);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    public record ImportOutcome(String status, Long activityId, boolean originalRetained, String code) {
        static ImportOutcome imported(long activityId, boolean retained) { return new ImportOutcome("IMPORTED", activityId, retained, null); }
        static ImportOutcome duplicate() { return new ImportOutcome("DUPLICATE", null, false, null); }
        static ImportOutcome skipped() { return new ImportOutcome("SKIPPED_NON_RUN", null, false, "NON_RUNNING"); }
        static ImportOutcome invalid(String code) { return new ImportOutcome("INVALID", null, false, code); }
        static ImportOutcome failed() { return new ImportOutcome("FAILED", null, false, "IMPORT_FAILED"); }
    }
    public record ImportBatch(List<ImportOutcome> outcomes, ImportBatchError batchError) { }
    public record ImportBatchError(String code, String message) { }
    private record StagedFile(Path path, String hash, long size) { }
    private record ActivityIdentity(long id, Double elapsedSeconds, Double distanceMeters) { }
}
