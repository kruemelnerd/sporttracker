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
    private final DataMutationCoordinator mutations;

    @org.springframework.beans.factory.annotation.Autowired
    public ImportService(GarminFitFixtureAdapter adapter, JdbcTemplate jdbc, TransactionTemplate transactions,
                          @Value("${running-dashboard.data-directory}") String dataDirectory, ImportProperties properties,
                          DataMutationCoordinator mutations) {
        this.adapter = adapter;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.properties = properties;
        this.mutations = mutations;
    }

    public ImportService(GarminFitFixtureAdapter adapter, JdbcTemplate jdbc, TransactionTemplate transactions,
                         String dataDirectory, ImportProperties properties) {
        this(adapter, jdbc, transactions, dataDirectory, properties, new DataMutationCoordinator());
    }

    public ImportBatch importFiles(List<MultipartFile> files) {
        try (var ignored = mutations.acquire()) {
            return importFilesLocked(files);
        }
    }

    private ImportBatch importFilesLocked(List<MultipartFile> files) {
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
        try {
            return importFit(file.getInputStream());
        } catch (Exception exception) {
            return ImportOutcome.failed();
        }
    }

    public ImportOutcome importFit(InputStream fitInput) {
        return importFit(fitInput, "MANUAL");
    }

    public ImportOutcome importFit(InputStream fitInput, String source) {
        if (!List.of("MANUAL", "GARMIN", "INTERVALS_ICU").contains(source)) throw new IllegalArgumentException("Unknown import source");
        try (var ignored = mutations.acquire()) {
            return importFitLocked(fitInput, source);
        }
    }

    private ImportOutcome importFitLocked(InputStream fitInput, String source) {
        if (fitInput == null) return ImportOutcome.invalid("EMPTY_FILE");
        StagedFile staged = null;
        try {
            staged = stage(fitInput);
            String hash = staged.hash();
            Long hashMatch = importedActivity(hash);
            GarminFitFixtureAdapter.DecodedActivity decoded;
            try (InputStream input = Files.newInputStream(staged.path())) {
                decoded = adapter.decode(input);
            }
            if (!"running".equals(decoded.sport())) return ImportOutcome.skipped();
            if (hashMatch != null) {
                transactions.execute(status -> mergeFitDetails(hashMatch, decoded));
                return ImportOutcome.duplicate(hashMatch);
            }
            List<Long> existing = existingActivities(decoded);
            if (existing.size() == 1) {
                Long activityId = existing.getFirst();
                long stagedSize = staged.size();
                transactions.execute(status -> {
                    jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes, source) VALUES (?, ?, ?, ?)",
                            activityId, hash, stagedSize, source);
                    mergeFitDetails(activityId, decoded);
                    return null;
                });
                return ImportOutcome.duplicate(activityId);
            }
            if (existing.size() > 1) return ImportOutcome.ambiguousDuplicate();
            StagedFile committedStaged = staged;
            Long activityId = transactions.execute(status -> persist(decoded, hash, committedStaged.size(), source));
            if (activityId == null) return ImportOutcome.failed();
            boolean retained = retainOriginal(hash, staged.path(), activityId);
            staged = null;
            return ImportOutcome.imported(activityId, retained);
        } catch (IOException exception) {
            return ImportOutcome.invalid(exception.getMessage() != null && exception.getMessage().contains("exactly one session") ? "MULTIPLE_ACTIVITIES" : "INVALID_FIT");
        } catch (DataIntegrityViolationException exception) {
            Long duplicateId = staged == null ? null : importedActivity(staged.hash());
            return duplicateId == null ? ImportOutcome.failed() : ImportOutcome.duplicate(duplicateId);
        } catch (Exception exception) {
            return ImportOutcome.failed();
        } finally {
            if (staged != null) deleteQuietly(staged.path());
        }
    }

    public void saveGarminTrainingMetrics(long activityId, GarminPersonalApiClient.TrainingMetrics metrics) {
        if (metrics == null) throw new IllegalArgumentException("Garmin training metrics are required");
        int updated = jdbc.update("UPDATE activity SET garmin_activity_name = COALESCE(?, garmin_activity_name), garmin_average_speed_mps = COALESCE(?, garmin_average_speed_mps), garmin_training_effect = COALESCE(?, garmin_training_effect), garmin_anaerobic_training_effect = COALESCE(?, garmin_anaerobic_training_effect), garmin_activity_training_load = COALESCE(?, garmin_activity_training_load), garmin_enrichment_complete = TRUE WHERE id = ?",
                metrics.activityName(), metrics.averageSpeedMetersPerSecond(), metrics.aerobicTrainingEffect(), metrics.anaerobicTrainingEffect(), metrics.trainingLoad(), activityId);
        if (updated != 1) throw new IllegalStateException("Imported activity is unavailable");
    }

    public void markGarminEnrichmentPending(long activityId) {
        if (jdbc.update("UPDATE activity SET garmin_enrichment_complete = FALSE WHERE id = ?", activityId) != 1)
            throw new IllegalStateException("Imported activity is unavailable");
    }

    private StagedFile stage(InputStream input) throws Exception {
        Path temporaryDirectory = dataDirectory.resolve(".incoming").normalize();
        if (!temporaryDirectory.startsWith(dataDirectory)) throw new IOException("invalid temporary path");
        Files.createDirectories(temporaryDirectory);
        Path path = Files.createTempFile(temporaryDirectory, "fit-", ".tmp");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (input; var output = Files.newOutputStream(path)) {
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

    private List<Long> existingActivities(GarminFitFixtureAdapter.DecodedActivity decoded) {
        List<ActivityIdentity> candidates = jdbc.query(
                "SELECT id, elapsed_seconds, distance_meters FROM activity WHERE sport = ? AND start_time = ?",
                (result, row) -> new ActivityIdentity(result.getLong("id"), (Double) result.getObject("elapsed_seconds"), (Double) result.getObject("distance_meters")),
                decoded.sport(), Timestamp.from(decoded.startTime()));
        return candidates.stream()
                .filter(candidate -> sameMeasurement(candidate.elapsedSeconds(), decoded.elapsedSeconds(), 2d)
                        && sameMeasurement(candidate.distanceMeters(), decoded.distanceMeters(), 5d))
                .map(ActivityIdentity::id)
                .toList();
    }

    private Long importedActivity(String hash) {
        return jdbc.query("SELECT activity_id FROM import_file WHERE sha256 = ?", (result, row) -> result.getLong(1), hash)
                .stream().findFirst().orElse(null);
    }

    private static boolean sameMeasurement(Double left, Double right, double tolerance) {
        return left != null && right != null && Math.abs(left - right) <= tolerance;
    }

    private Long persist(GarminFitFixtureAdapter.DecodedActivity decoded, String hash, long sizeBytes, String source) {
        Long activityId = jdbc.queryForObject("INSERT INTO activity (sport, sub_sport, start_time, elapsed_seconds, moving_seconds, distance_meters, average_speed_mps, average_heart_rate, maximum_heart_rate, average_cadence, ascent_meters, calories) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, decoded.sport(), decoded.subSport(), Timestamp.from(decoded.startTime()), decoded.elapsedSeconds(), decoded.movingSeconds(), decoded.distanceMeters(), decoded.averageSpeedMetersPerSecond(), decoded.averageHeartRate(), decoded.maximumHeartRate(), decoded.averageCadence(), decoded.ascentMeters(), decoded.calories());
        jdbc.update("INSERT INTO import_file (activity_id, sha256, size_bytes, source) VALUES (?, ?, ?, ?)", activityId, hash, sizeBytes, source);
        insertRecords(activityId, decoded.records());
        for (GarminFitFixtureAdapter.DecodedWorkoutStep step : decoded.workoutSteps()) {
            jdbc.update("INSERT INTO activity_workout_step (activity_id, step_index, step_name, duration_type, planned_duration_seconds, planned_distance_meters, repeat_from_step_index, repeat_count, intensity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, step.stepIndex(), step.name(), step.durationType(), step.plannedDurationSeconds(),
                    step.plannedDistanceMeters(), step.repeatFromStepIndex(), step.repeatCount(), step.intensity());
        }
        insertLaps(activityId, decoded.laps());
        int pauseNumber = 1;
        for (GarminFitFixtureAdapter.DecodedPause pause : decoded.pauses()) {
            if (pause.startElapsedSeconds() != null && pause.endElapsedSeconds() != null
                    && pause.startElapsedSeconds() >= 0 && pause.endElapsedSeconds() > pause.startElapsedSeconds()) {
                jdbc.update("INSERT INTO activity_pause (activity_id, pause_number, start_elapsed_seconds, end_elapsed_seconds) VALUES (?, ?, ?, ?)",
                        activityId, pauseNumber++, pause.startElapsedSeconds(), pause.endElapsedSeconds());
            }
        }
        return activityId;
    }

    private Void mergeFitDetails(long activityId, GarminFitFixtureAdapter.DecodedActivity decoded) {
        int storedRecords = jdbc.queryForObject("SELECT count(*) FROM activity_record WHERE activity_id = ?", Integer.class, activityId);
        if (storedRecords == 0) {
            insertRecords(activityId, decoded.records());
        } else if (storedRecords == decoded.records().size() && recordsAlign(activityId, decoded.records())) {
            mergeRecords(activityId, decoded.records());
        }

        int storedLaps = jdbc.queryForObject("SELECT count(*) FROM lap WHERE activity_id = ?", Integer.class, activityId);
        if (storedLaps == 0) {
            insertLaps(activityId, decoded.laps());
        } else if (storedLaps == decoded.laps().size()) {
            mergeLaps(activityId, decoded.laps());
        }

        for (GarminFitFixtureAdapter.DecodedWorkoutStep step : decoded.workoutSteps()) {
            jdbc.update("INSERT INTO activity_workout_step (activity_id, step_index, step_name, duration_type, planned_duration_seconds, planned_distance_meters, repeat_from_step_index, repeat_count, intensity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (activity_id, step_index) DO UPDATE SET step_name = COALESCE(activity_workout_step.step_name, EXCLUDED.step_name), duration_type = COALESCE(activity_workout_step.duration_type, EXCLUDED.duration_type), planned_duration_seconds = COALESCE(activity_workout_step.planned_duration_seconds, EXCLUDED.planned_duration_seconds), planned_distance_meters = COALESCE(activity_workout_step.planned_distance_meters, EXCLUDED.planned_distance_meters), repeat_from_step_index = COALESCE(activity_workout_step.repeat_from_step_index, EXCLUDED.repeat_from_step_index), repeat_count = COALESCE(activity_workout_step.repeat_count, EXCLUDED.repeat_count), intensity = COALESCE(activity_workout_step.intensity, EXCLUDED.intensity)",
                    activityId, step.stepIndex(), step.name(), step.durationType(), step.plannedDurationSeconds(),
                    step.plannedDistanceMeters(), step.repeatFromStepIndex(), step.repeatCount(), step.intensity());
        }

        int storedPauses = jdbc.queryForObject("SELECT count(*) FROM activity_pause WHERE activity_id = ?", Integer.class, activityId);
        if (storedPauses == 0 || storedPauses == decoded.pauses().size()) {
            int pauseNumber = 1;
            for (GarminFitFixtureAdapter.DecodedPause pause : decoded.pauses()) {
                if (pause.startElapsedSeconds() != null && pause.endElapsedSeconds() != null
                        && pause.startElapsedSeconds() >= 0 && pause.endElapsedSeconds() > pause.startElapsedSeconds()) {
                    jdbc.update("INSERT INTO activity_pause (activity_id, pause_number, start_elapsed_seconds, end_elapsed_seconds) VALUES (?, ?, ?, ?) ON CONFLICT (activity_id, pause_number) DO NOTHING",
                            activityId, pauseNumber++, pause.startElapsedSeconds(), pause.endElapsedSeconds());
                }
            }
        }
        return null;
    }

    private boolean recordsAlign(long activityId, List<GarminFitFixtureAdapter.DecodedRecord> records) {
        List<ExistingRecord> stored = jdbc.query("SELECT elapsed_seconds, distance_meters FROM activity_record WHERE activity_id = ? ORDER BY record_index",
                (result, row) -> new ExistingRecord(result.getObject(1, Double.class), result.getObject(2, Double.class)), activityId);
        for (int index = 0; index < stored.size(); index++) {
            var source = records.get(index);
            if (!sameNullableMeasurement(stored.get(index).elapsedSeconds(), source.elapsedSeconds(), 0.001d)
                    || !sameNullableMeasurement(stored.get(index).distanceMeters(), source.distanceMeters(), 0.001d)) return false;
        }
        return true;
    }

    private static boolean sameNullableMeasurement(Double left, Double right, double tolerance) {
        return left == null || right == null ? left == right : Math.abs(left - right) <= tolerance;
    }

    private void mergeRecords(long activityId, List<GarminFitFixtureAdapter.DecodedRecord> records) {
        for (int index = 0; index < records.size(); index++) {
            var record = records.get(index);
            jdbc.update("UPDATE activity_record SET step_length_m = COALESCE(step_length_m, ?), vertical_oscillation_cm = COALESCE(vertical_oscillation_cm, ?), vertical_ratio_percent = COALESCE(vertical_ratio_percent, ?), ground_contact_time_ms = COALESCE(ground_contact_time_ms, ?), ground_contact_balance_percent = COALESCE(ground_contact_balance_percent, ?), temperature_celsius = COALESCE(temperature_celsius, ?), power_watts = COALESCE(power_watts, ?), altitude_meters = COALESCE(altitude_meters, ?), speed_mps = COALESCE(speed_mps, ?), heart_rate = COALESCE(heart_rate, ?), cadence = COALESCE(cadence, ?) WHERE activity_id = ? AND record_index = ?",
                    record.stepLengthMeters(), record.verticalOscillationCentimeters(), record.verticalRatioPercent(),
                    record.groundContactTimeMilliseconds(), record.groundContactBalancePercent(),
                    record.temperatureCelsius(), record.powerWatts(), record.altitudeMeters(),
                    record.speedMetersPerSecond(), record.heartRate(), record.cadence(), activityId, index);
        }
    }

    private void mergeLaps(long activityId, List<GarminFitFixtureAdapter.DecodedLap> laps) {
        for (var lap : laps) {
            jdbc.update("UPDATE lap SET workout_step_index = COALESCE(workout_step_index, ?), repetition_number = COALESCE(repetition_number, ?), start_elapsed_seconds = COALESCE(start_elapsed_seconds, ?), end_elapsed_seconds = COALESCE(end_elapsed_seconds, ?), start_distance_meters = COALESCE(start_distance_meters, ?), end_distance_meters = COALESCE(end_distance_meters, ?) WHERE activity_id = ? AND lap_number = ?",
                    lap.workoutStepIndex(), lap.repetitionNumber(), lap.startElapsedSeconds(), lap.endElapsedSeconds(),
                    lap.startDistanceMeters(), lap.endDistanceMeters(), activityId, lap.lapNumber());
        }
    }

    private void insertRecords(long activityId, List<GarminFitFixtureAdapter.DecodedRecord> records) {
        for (int index = 0; index < records.size(); index++) {
            var record = records.get(index);
            jdbc.update("INSERT INTO activity_record (activity_id, record_index, record_time, elapsed_seconds, distance_meters, latitude, longitude, altitude_meters, speed_mps, heart_rate, cadence, descent_meters, step_length_m, vertical_oscillation_cm, vertical_ratio_percent, ground_contact_time_ms, ground_contact_balance_percent, temperature_celsius, power_watts) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, index, record.timestamp() == null ? null : Timestamp.from(record.timestamp()), record.elapsedSeconds(),
                    record.distanceMeters(), record.latitude(), record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(),
                    record.heartRate(), record.cadence(), record.descentMeters(), record.stepLengthMeters(),
                    record.verticalOscillationCentimeters(), record.verticalRatioPercent(), record.groundContactTimeMilliseconds(),
                    record.groundContactBalancePercent(), record.temperatureCelsius(), record.powerWatts());
        }
    }

    private void insertLaps(long activityId, List<GarminFitFixtureAdapter.DecodedLap> laps) {
        for (var lap : laps) {
            jdbc.update("INSERT INTO lap (activity_id, lap_number, duration_seconds, distance_meters, average_speed_mps, average_heart_rate, average_cadence, ascent_meters, descent_meters, workout_step_index, repetition_number, start_elapsed_seconds, end_elapsed_seconds, start_distance_meters, end_distance_meters) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    activityId, lap.lapNumber(), lap.durationSeconds(), lap.distanceMeters(), lap.averageSpeedMetersPerSecond(),
                    lap.averageHeartRate(), lap.averageCadence(), lap.ascentMeters(), lap.descentMeters(), lap.workoutStepIndex(),
                    lap.repetitionNumber(), lap.startElapsedSeconds(), lap.endElapsedSeconds(), lap.startDistanceMeters(),
                    lap.endDistanceMeters());
        }
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
        static ImportOutcome duplicate(Long activityId) { return new ImportOutcome("DUPLICATE", activityId, false, null); }
        static ImportOutcome ambiguousDuplicate() { return new ImportOutcome("DUPLICATE", null, false, "AMBIGUOUS_ACTIVITY_MATCH"); }
        static ImportOutcome skipped() { return new ImportOutcome("SKIPPED_NON_RUN", null, false, "NON_RUNNING"); }
        static ImportOutcome invalid(String code) { return new ImportOutcome("INVALID", null, false, code); }
        static ImportOutcome failed() { return new ImportOutcome("FAILED", null, false, "IMPORT_FAILED"); }
    }
    public record ImportBatch(List<ImportOutcome> outcomes, ImportBatchError batchError) { }
    public record ImportBatchError(String code, String message) { }
    private record StagedFile(Path path, String hash, long size) { }
    private record ActivityIdentity(long id, Double elapsedSeconds, Double distanceMeters) { }
    private record ExistingRecord(Double elapsedSeconds, Double distanceMeters) { }
}
