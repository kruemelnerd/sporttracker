package local.runningdashboard;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(ImportService.class)
public class IntervalsIcuSyncService {
    private final ActivityImportSource source;
    private final ImportService imports;
    private final ActivityImportSourceRepository mappings;
    private final IntervalsIcuProperties properties;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Instant lastAttemptAt;
    private volatile Instant lastSuccessfulSyncAt;
    private volatile Instant retryAfterUntil;
    private volatile SyncResult lastResult;

    @Autowired
    public IntervalsIcuSyncService(ActivityImportSource source, ImportService imports,
                                   ActivityImportSourceRepository mappings, IntervalsIcuProperties properties) {
        this(source, imports, mappings, properties, Clock.systemUTC());
    }

    IntervalsIcuSyncService(ActivityImportSource source, ImportService imports,
                            ActivityImportSourceRepository mappings, IntervalsIcuProperties properties, Clock clock) {
        this.source = source;
        this.imports = imports;
        this.mappings = mappings;
        this.properties = properties;
        this.clock = clock;
    }

    public SyncResult synchronize() {
        if (!properties.configured()) return SyncResult.notConfigured();
        if (!running.compareAndSet(false, true)) return SyncResult.notStarted("A sync is already running.");
        return runClaimed();
    }

    public boolean startAsync(TaskExecutor executor) {
        if (!properties.configured() || !running.compareAndSet(false, true)) return false;
        Instant now = clock.instant();
        if (retryAfterUntil != null && now.isBefore(retryAfterUntil)) {
            running.set(false);
            return false;
        }
        try {
            executor.execute(this::runClaimed);
            return true;
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    private SyncResult runClaimed() {
        try {
            Instant now = clock.instant();
            if (retryAfterUntil != null && now.isBefore(retryAfterUntil)) return SyncResult.notStarted("Sync paused until the upstream rate limit clears.");
            lastAttemptAt = now;
            LocalDate newest = LocalDate.now(clock);
            LocalDate oldest = lastSuccessfulSyncAt == null ? newest.minusDays(properties.initialLookbackDays()) : newest.minusDays(14);
            var counts = new Counts();
            List<ActivityResult> results = new ArrayList<>();
            try {
                for (ActivityImportSource.RemoteActivity activity : source.listActivities(oldest, newest)) {
                    counts.checked++;
                    if (activity == null || activity.id() == null || activity.id().isBlank()) {
                        counts.failed++;
                        results.add(new ActivityResult(null, "FAILED", "INVALID_EXTERNAL_ACTIVITY", "Aktivität enthält keine gültige externe ID."));
                        continue;
                    }
                    if (!isRun(activity.type())) {
                        counts.skipped++;
                        results.add(new ActivityResult(activity.id(), "SKIPPED", "NON_RUNNING", "Übersprungen: keine Laufaktivität."));
                        continue;
                    }
                    if (mappings.findActivityId(source.provider(), activity.id()).isPresent()) {
                        counts.alreadyPresent++;
                        results.add(new ActivityResult(activity.id(), "ALREADY_PRESENT", null, "Aktivität bereits verknüpft."));
                        continue;
                    }
                    try {
                        ActivityImportSource.ActivityFile file = source.downloadActivityFile(activity.id());
                        if (file == null || file.body() == null) {
                            skipUnavailable(activity, counts, results);
                            continue;
                        }
                        PushbackInputStream fit = gzipFit(file);
                        if (fit == null) {
                            counts.skipped++;
                            results.add(new ActivityResult(activity.id(), "SKIPPED", "NO_FIT_FILE", "Übersprungen: keine nutzbare FIT-Datei verfügbar."));
                            continue;
                        }
                        ImportService.ImportOutcome outcome = imports.importFit(fit);
                        switch (outcome.status()) {
                            case "IMPORTED" -> link(activity, outcome.activityId(), true, counts, results);
                            case "DUPLICATE" -> {
                                if (outcome.activityId() == null) {
                                    counts.failed++;
                                    results.add(new ActivityResult(activity.id(), "FAILED", outcome.code(), "Lokale FIT-Dublette ist nicht eindeutig zuordenbar."));
                                } else link(activity, outcome.activityId(), false, counts, results);
                            }
                            case "SKIPPED_NON_RUN" -> {
                                counts.skipped++;
                                results.add(new ActivityResult(activity.id(), "SKIPPED", outcome.code(), "FIT-Datei enthält kein Lauftraining."));
                            }
                            default -> {
                                counts.failed++;
                                results.add(new ActivityResult(activity.id(), "FAILED", outcome.code(), "FIT-Datei konnte nicht importiert werden."));
                            }
                        }
                    } catch (IntervalsIcuClientException exception) {
                        if ("FILE_NOT_AVAILABLE".equals(exception.code())) skipUnavailable(activity, counts, results);
                        else {
                            counts.failed++;
                            results.add(new ActivityResult(activity.id(), "FAILED", exception.code(), exception.getMessage()));
                            if ("RATE_LIMITED".equals(exception.code())) {
                                retryAfterUntil = now.plus(exception.retryAfter() == null ? properties.syncInterval() : exception.retryAfter());
                                break;
                            }
                        }
                    } catch (IOException exception) {
                        counts.failed++;
                        results.add(new ActivityResult(activity.id(), "FAILED", "INVALID_GZIP", "Aktivitätsdatei ist beschädigt oder kein gültiges gzip."));
                    } catch (RuntimeException exception) {
                        counts.failed++;
                        results.add(new ActivityResult(activity.id(), "FAILED", "ACTIVITY_IMPORT_FAILED", "Aktivität konnte nicht verarbeitet werden."));
                    }
                }
            } catch (IntervalsIcuClientException exception) {
                counts.failed++;
                results.add(new ActivityResult(null, "FAILED", exception.code(), exception.getMessage()));
                if ("RATE_LIMITED".equals(exception.code())) {
                    retryAfterUntil = now.plus(exception.retryAfter() == null ? properties.syncInterval() : exception.retryAfter());
                }
            } catch (RuntimeException exception) {
                counts.failed++;
                results.add(new ActivityResult(null, "FAILED", "SYNC_FAILED", "Aktivitäten konnten nicht abgerufen werden."));
            }
            Instant completedAt = clock.instant();
            if (counts.failed == 0) lastSuccessfulSyncAt = completedAt;
            lastResult = new SyncResult(true, true, counts.checked, counts.imported, counts.alreadyPresent,
                    counts.skipped, counts.failed, results, results.stream().filter(result -> "FAILED".equals(result.status())).map(ActivityResult::message).findFirst().orElse(null), lastAttemptAt, completedAt);
            return lastResult;
        } finally {
            running.set(false);
        }
    }

    public boolean configured() { return properties.configured(); }
    public boolean running() { return running.get(); }
    public Instant lastAttemptAt() { return lastAttemptAt; }
    public Instant lastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
    public Instant retryAfterUntil() { return retryAfterUntil; }
    public SyncResult lastResult() { return lastResult; }

    private void link(ActivityImportSource.RemoteActivity activity, Long activityId, boolean imported,
                      Counts counts, List<ActivityResult> results) {
        if (activityId == null) {
            counts.failed++;
            results.add(new ActivityResult(activity.id(), "FAILED", "ACTIVITY_ID_MISSING", "FIT-Import lieferte keine lokale Aktivität."));
        } else if (mappings.link(source.provider(), activity.id(), activityId)) {
            if (imported) counts.imported++;
            else counts.alreadyPresent++;
            results.add(new ActivityResult(activity.id(), imported ? "IMPORTED" : "ALREADY_PRESENT", null,
                    imported ? "Lauf importiert." : "Lauf war bereits lokal vorhanden."));
        } else if (mappings.findActivityId(source.provider(), activity.id()).filter(activityId::equals).isPresent()) {
            counts.alreadyPresent++;
            results.add(new ActivityResult(activity.id(), "ALREADY_PRESENT", null, "Aktivität bereits verknüpft."));
        } else {
            counts.failed++;
            results.add(new ActivityResult(activity.id(), "FAILED", "EXTERNAL_ID_CONFLICT", "Externe ID ist bereits einer anderen Aktivität zugeordnet."));
        }
    }

    private static void skipUnavailable(ActivityImportSource.RemoteActivity activity, Counts counts, List<ActivityResult> results) {
        counts.skipped++;
        results.add(new ActivityResult(activity.id(), "SKIPPED", "FILE_NOT_AVAILABLE", "Intervals.icu stellt die Aktivitätsdatei nicht bereit."));
    }

    private static PushbackInputStream gzipFit(ActivityImportSource.ActivityFile file) throws IOException {
        byte[] body = file.body();
        boolean gzipped = body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
        if (!gzipped && !isFit(body) && !isXmlTrack(body)) throw new java.util.zip.ZipException("Original file is not gzip-compressed");
        InputStream original = gzipped ? new GZIPInputStream(new ByteArrayInputStream(body)) : new ByteArrayInputStream(body);
        var input = new PushbackInputStream(original, 512);
        byte[] prefix = input.readNBytes(512);
        input.unread(prefix);
        if (isFit(prefix)) return input;
        input.close();
        return null;
    }

    private static boolean isFit(byte[] bytes) {
        return bytes.length >= 12 && bytes[8] == '.' && bytes[9] == 'F' && bytes[10] == 'I' && bytes[11] == 'T';
    }

    private static boolean isXmlTrack(byte[] bytes) {
        String prefix = new String(bytes, 0, Math.min(bytes.length, 512), java.nio.charset.StandardCharsets.UTF_8)
                .stripLeading().toLowerCase(java.util.Locale.ROOT);
        return prefix.startsWith("<") && (prefix.contains("<gpx") || prefix.contains("<trainingcenterdatabase"));
    }

    private static boolean isRun(String type) {
        return type != null && type.toLowerCase(java.util.Locale.ROOT).contains("run");
    }

    private static final class Counts {
        private int checked;
        private int imported;
        private int alreadyPresent;
        private int skipped;
        private int failed;
    }

    public record ActivityResult(String externalId, String status, String code, String message) { }
    public record SyncResult(boolean started, boolean configured, int checked, int imported, int alreadyPresent,
                             int skipped, int failed, List<ActivityResult> activities, String error,
                             Instant attemptedAt, Instant completedAt) {
        static SyncResult notConfigured() { return new SyncResult(false, false, 0, 0, 0, 0, 0, List.of(), "Intervals.icu is not configured.", null, null); }
        static SyncResult notStarted(String message) { return new SyncResult(false, true, 0, 0, 0, 0, 0, List.of(), message, null, null); }
    }
}
