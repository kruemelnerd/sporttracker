package local.runningdashboard;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(ImportService.class)
public class GarminSyncService {
    private final ActivityImportSource source;
    private final ImportService imports;
    private final ActivityImportSourceRepository mappings;
    private final GarminPersonalApiProperties properties;
    private final GarminSyncStateRepository stateRepository;
    private final Clock clock;
    private final ZoneId dateZone;
    private final AtomicBoolean running = new AtomicBoolean();

    @org.springframework.beans.factory.annotation.Autowired
    public GarminSyncService(@org.springframework.beans.factory.annotation.Qualifier("garminActivityImportSource") ActivityImportSource source,
                             ImportService imports, ActivityImportSourceRepository mappings,
                             GarminPersonalApiProperties properties, GarminSyncStateRepository stateRepository,
                             @Value("${running-dashboard.display-timezone:Europe/Berlin}") ZoneId dateZone) {
        this(source, imports, mappings, properties, stateRepository, Clock.systemUTC(), dateZone);
    }

    GarminSyncService(ActivityImportSource source, ImportService imports, ActivityImportSourceRepository mappings,
                      GarminPersonalApiProperties properties, GarminSyncStateRepository stateRepository,
                      Clock clock, ZoneId dateZone) {
        this.source = source;
        this.imports = imports;
        this.mappings = mappings;
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.clock = clock;
        this.dateZone = dateZone;
    }

    public boolean startAsync(TaskExecutor executor) {
        if (!properties.configured() || !running.compareAndSet(false, true)) return false;
        Instant now = clock.instant();
        var old = stateRepository.find().orElse(null);
        if (old != null && old.retryAfterUntil() != null && now.isBefore(old.retryAfterUntil())) {
            running.set(false);
            return false;
        }
        try { executor.execute(this::runClaimed); }
        catch (RuntimeException failure) { running.set(false); throw failure; }
        return true;
    }

    public SyncResult synchronize() {
        if (!properties.configured()) return result(false, 0, 0, 0, 0, 0, "NOT_CONFIGURED", null, null);
        if (!running.compareAndSet(false, true)) return result(false, 0, 0, 0, 0, 0, "SYNC_ALREADY_RUNNING", null, null);
        var old = stateRepository.find().orElse(null);
        if (old != null && old.retryAfterUntil() != null && clock.instant().isBefore(old.retryAfterUntil())) {
            running.set(false);
            return result(false, 0, 0, 0, 0, 0, "RATE_LIMITED", old.lastAttemptAt(), null);
        }
        return runClaimed();
    }

    private SyncResult runClaimed() {
        Instant attemptedAt = clock.instant();
        var previous = stateRepository.find().orElse(null);
        LocalDate newest = LocalDate.now(clock.withZone(dateZone));
        LocalDate oldest = previous == null || previous.lastSuccessfulSyncAt() == null
                ? newest.minusDays(properties.initialLookbackDays()) : newest.minusDays(14);
        Counts counts = new Counts();
        String error = null;
        Instant retryUntil = null;
        Instant connectedAt = previous == null ? null : previous.lastSuccessfulAuthenticationAt();
        try {
            List<ActivityImportSource.RemoteActivity> activities = source.listActivities(oldest, newest);
            connectedAt = clock.instant();
            for (var activity : activities) {
                counts.checked++;
                if (activity == null || activity.id() == null || activity.id().isBlank()) {
                    counts.failed++; error = "INVALID_EXTERNAL_ACTIVITY"; continue;
                }
                if (!isRun(activity.type())) { counts.skipped++; continue; }
                if (mappings.findActivityId("GARMIN", activity.id()).isPresent()) { counts.alreadyPresent++; continue; }
                try {
                    var file = source.downloadActivityFile(activity.id());
                    if (file == null || file.body() == null) { counts.skipped++; continue; }
                    var outcome = imports.importFit(new ByteArrayInputStream(file.body()));
                    switch (outcome.status()) {
                        case "IMPORTED" -> {
                            String linkError = link(activity.id(), outcome.activityId(), counts, true);
                            if (linkError != null) error = linkError;
                        }
                        case "DUPLICATE" -> {
                            if (outcome.activityId() == null) { counts.failed++; error = "AMBIGUOUS_ACTIVITY_MATCH"; }
                            else {
                                String linkError = link(activity.id(), outcome.activityId(), counts, false);
                                if (linkError != null) error = linkError;
                            }
                        }
                        case "SKIPPED_NON_RUN" -> counts.skipped++;
                        default -> { counts.failed++; error = outcome.code() == null ? "ACTIVITY_IMPORT_FAILED" : outcome.code(); }
                    }
                } catch (GarminClientException failure) {
                    counts.failed++;
                    error = failure.code();
                    if ("RATE_LIMITED".equals(error)) {
                        retryUntil = clock.instant().plus(failure.retryAfter() == null ? properties.syncInterval() : failure.retryAfter());
                        break;
                    }
                } catch (RuntimeException failure) {
                    counts.failed++;
                    error = "ACTIVITY_IMPORT_FAILED";
                }
            }
        } catch (GarminClientException failure) {
            counts.failed++;
            error = failure.code();
            if ("RATE_LIMITED".equals(error)) retryUntil = clock.instant().plus(failure.retryAfter() == null ? properties.syncInterval() : failure.retryAfter());
        } catch (RuntimeException failure) {
            counts.failed++;
            error = "SYNC_FAILED";
        }
        Instant completedAt = clock.instant();
        boolean success = error == null && counts.failed == 0;
        var saved = new GarminSyncStateRepository.SyncState(attemptedAt,
                success ? completedAt : previous == null ? null : previous.lastSuccessfulSyncAt(), connectedAt,
                retryUntil, counts.checked, counts.imported, counts.alreadyPresent, counts.skipped, counts.failed, error);
        try { stateRepository.save(saved); }
        finally { running.set(false); }
        return new SyncResult(true, counts.checked, counts.imported, counts.alreadyPresent,
                counts.skipped, counts.failed, error, attemptedAt, completedAt);
    }

    private String link(String externalId, Long activityId, Counts counts, boolean imported) {
        if (activityId == null) { counts.failed++; return "ACTIVITY_ID_MISSING"; }
        if (mappings.link("GARMIN", externalId, activityId)
                || mappings.findActivityId("GARMIN", externalId).filter(activityId::equals).isPresent()) {
            if (imported) counts.imported++; else counts.alreadyPresent++;
            return null;
        }
        counts.failed++;
        return "EXTERNAL_ID_CONFLICT";
    }

    private static boolean isRun(String type) {
        if (type == null) return false;
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "running", "trail_running", "virtual_run" -> true;
            default -> false;
        };
    }

    public Status status(boolean automaticSyncSuppressed) {
        var state = stateRepository.find().orElse(null);
        SyncResult result = state == null || state.lastAttemptAt() == null ? null : new SyncResult(true,
                state.checked(), state.imported(), state.alreadyPresent(), state.skipped(), state.failed(),
                state.lastErrorCode(), state.lastAttemptAt(), state.lastAttemptAt());
        return new Status(properties.enabled(), properties.apiKey() != null && !properties.apiKey().isBlank(),
                properties.configured(), state != null && state.lastSuccessfulAuthenticationAt() != null,
                running.get(), automaticSyncSuppressed,
                state == null ? null : state.lastAttemptAt(), state == null ? null : state.lastSuccessfulSyncAt(),
                state == null ? null : state.retryAfterUntil(), result);
    }

    private static SyncResult result(boolean started, int checked, int imported, int present, int skipped, int failed,
                                     String error, Instant attempted, Instant completed) {
        return new SyncResult(started, checked, imported, present, skipped, failed, error, attempted, completed);
    }

    public boolean configured() { return properties.configured(); }
    public boolean running() { return running.get(); }
    private static final class Counts { int checked; int imported; int alreadyPresent; int skipped; int failed; }
    public record SyncResult(boolean started, int checked, int imported, int alreadyPresent, int skipped, int failed,
                             String error, Instant attemptedAt, Instant completedAt) { }
    public record Status(boolean enabled, boolean apiKeyPresent, boolean configured, boolean connected, boolean running,
                         boolean automaticSyncSuppressed, Instant lastAttemptAt, Instant lastSuccessfulSyncAt,
                         Instant retryAfterUntil, SyncResult lastResult) { }
}
