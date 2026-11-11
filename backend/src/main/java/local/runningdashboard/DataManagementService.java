package local.runningdashboard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DataManagementService {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);
    private final DataManagementRepository repository;
    private final SyncPauseRepository pauses;
    private final DataMutationCoordinator mutations;
    private final TransactionTemplate transactions;
    private final OriginalFileDeletionWorker fileDeletionWorker;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Pending> confirmations = new ConcurrentHashMap<>();

    public DataManagementService(DataManagementRepository repository, SyncPauseRepository pauses,
                                DataMutationCoordinator mutations, PlatformTransactionManager transactionManager,
                                OriginalFileDeletionWorker fileDeletionWorker) {
        this.repository = repository;
        this.pauses = pauses;
        this.mutations = mutations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.fileDeletionWorker = fileDeletionWorker;
    }

    public Preview preview(Action action) {
        var lease = mutations.tryAcquire().orElseThrow(() -> new DataManagementException(409, "WRITE_IN_PROGRESS"));
        try (lease) {
            State state = inspect(action);
            String token = UUID.randomUUID().toString();
            Instant expiresAt = clock.instant().plus(TOKEN_LIFETIME);
            confirmations.put(token, new Pending(action, state.fingerprint(), expiresAt));
            return new Preview(token, action, state.activities().size(), state.deletable().size(), state.protectedCount(),
                    state.ambiguousCount(), state.associations(), state.records(), state.laps(), state.importFiles(),
                    state.plans(), state.phases(), state.sessions(), expiresAt, preservedData(action));
        }
    }

    public Result confirm(Action action, String token, String phrase) {
        var lease = mutations.tryAcquire().orElseThrow(() -> new DataManagementException(409, "WRITE_IN_PROGRESS"));
        try (lease) {
            Pending pending = token == null ? null : confirmations.remove(token);
            if (pending == null || !pending.action().equals(action) || !clock.instant().isBefore(pending.expiresAt())) {
                throw new DataManagementException(410, "PREVIEW_EXPIRED");
            }
            if (!action.phrase.equals(phrase)) throw new DataManagementException(400, "CONFIRMATION_PHRASE_MISMATCH");
            State state = inspect(action);
            if (!pending.fingerprint().equals(state.fingerprint())) throw new DataManagementException(409, "PREVIEW_STALE");
            AtomicReference<OriginalFileDeletionWorker.CleanupReport> cleanup = new AtomicReference<>();
            Result result = transactions.execute(status -> {
                Result deleted = delete(action, state);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { cleanup.set(fileDeletionWorker.processPendingWithReport()); }
                });
                return deleted;
            });
            return !TransactionSynchronizationManager.isActualTransactionActive() && cleanup.get() != null
                    ? result.withCleanup(cleanup.get()) : result;
        }
    }

    private Result delete(Action action, State state) {
        List<Long> ids = state.deletable().stream().map(DataManagementRepository.ActivityState::id).toList();
        int linksDeleted = 0;
        int activitiesDeleted;
        if (action == Action.DELETE_GARMIN || action == Action.DELETE_INTERVALS_ICU) {
            String provider = action.provider;
            pauses.pause(provider);
            repository.enqueueFiles(state.deletable().stream().flatMap(s -> s.files().stream()).distinct().toList());
            linksDeleted = repository.deleteMappings(provider);
            activitiesDeleted = repository.deleteActivities(ids);
        } else {
            pauses.pause("GARMIN");
            pauses.pause("INTERVALS_ICU");
            repository.enqueueFiles(state.deletable().stream().flatMap(s -> s.files().stream()).distinct().toList());
            activitiesDeleted = repository.deleteAllActivities();
            repository.clearGarminSyncState();
            if (action == Action.RESET_APPLICATION_DATA) repository.deletePlans();
            linksDeleted = Math.toIntExact(state.associations());
        }
        return new Result(action, activitiesDeleted, state.protectedCount(), state.ambiguousCount(), linksDeleted,
                state.records(), state.laps(), state.importFiles(), state.plans(), state.phases(), state.sessions(),
                0, state.deletable().stream().flatMap(s -> s.files().stream()).distinct().count(), 0,
                pauses.isPaused("GARMIN"), pauses.isPaused("INTERVALS_ICU"));
    }

    private State inspect(Action action) {
        List<DataManagementRepository.ActivityState> activities = repository.capture(action);
        List<DataManagementRepository.ActivityState> deletable = new ArrayList<>();
        int protectedCount = 0;
        int ambiguous = 0;
        for (var activity : activities) {
            if (action.provider == null) { deletable.add(activity); continue; }
            boolean ambiguousSource = activity.importSources().isEmpty() || activity.importSources().stream().anyMatch(source -> source == null);
            if (ambiguousSource) ambiguous++;
            if (!ambiguousSource && activity.importSources().stream().allMatch(action.provider::equals)
                    && activity.providers().stream().allMatch(source -> source.startsWith(action.provider + ":"))) deletable.add(activity);
            else protectedCount++;
        }
        long associations = action.provider == null ? activities.stream().mapToLong(a -> a.providers().size()).sum()
                : repository.providerAssociations(action.provider);
        List<Long> ids = deletable.stream().map(DataManagementRepository.ActivityState::id).toList();
        long records = action.provider == null ? repository.count("activity_record") : repository.countForActivities("activity_record", ids);
        long laps = action.provider == null ? repository.count("lap") : repository.countForActivities("lap", ids);
        long imports = action.provider == null ? repository.count("import_file") : repository.countForActivities("import_file", ids);
        long plans = action == Action.RESET_APPLICATION_DATA ? repository.count("training_plan") : 0;
        long phases = action == Action.RESET_APPLICATION_DATA ? repository.count("training_phase") : 0;
        long sessions = action == Action.RESET_APPLICATION_DATA ? repository.count("planned_session") : 0;
        String fingerprint = fingerprint(action, activities, action == Action.RESET_APPLICATION_DATA ? repository.planResetFingerprint() : "");
        return new State(activities, deletable, protectedCount, ambiguous, associations, records, laps, imports,
                plans, phases, sessions, fingerprint);
    }

    private static String fingerprint(Action action, List<DataManagementRepository.ActivityState> activities, String domainState) {
        String snapshot = action + "|" + domainState + "|" + activities.stream().map(a -> a.id() + ":" + a.importSources() + ":" + a.providers() + ":" + a.fileStates() + ":" + a.records() + ":" + a.laps()).toList();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String preservedData(Action action) {
        return switch (action) {
            case DELETE_GARMIN, DELETE_INTERVALS_ICU -> "Manuelle, ältere, unbekannte und gemeinsam zugeordnete Aktivitäten bleiben erhalten; Trainingspläne bleiben erhalten.";
            case DELETE_ACTIVITIES -> "Trainingspläne, Phasen und geplante Einheiten bleiben erhalten.";
            case RESET_APPLICATION_DATA -> "Datenbankschema, Flyway-Historie, Konfiguration, Secrets, Volumes, Container und Dateien außerhalb des verwalteten Datenverzeichnisses bleiben erhalten.";
        };
    }

    public enum Action {
        DELETE_GARMIN("GARMIN LÖSCHEN", "GARMIN"),
        DELETE_INTERVALS_ICU("INTERVALS. ICU LÖSCHEN", "INTERVALS_ICU"),
        DELETE_ACTIVITIES("ALLE AKTIVITÄTEN LÖSCHEN", null),
        RESET_APPLICATION_DATA("ALLE ANWENDUNGSDATEN LÖSCHEN", null);
        private final String phrase;
        private final String provider;
        Action(String phrase, String provider) { this.phrase = phrase; this.provider = provider; }
    }

    public record Preview(String token, Action action, int affectedActivities, int deletableActivities,
                          int protectedActivities, int ambiguousActivities, long sourceAssociations,
                          long records, long laps, long importFiles, long trainingPlans, long trainingPhases,
                          long plannedSessions, Instant expiresAt, String preservedData) { }
    public record Result(Action action, int activitiesDeleted, int activitiesProtected, int activitiesAmbiguous,
                         int sourceAssociationsDeleted, long recordsDeleted, long lapsDeleted, long importFilesDeleted,
                         long trainingPlansDeleted, long trainingPhasesDeleted, long plannedSessionsDeleted,
                         int filesDeleted, long filesPending, int filesFailed, boolean garminPaused, boolean intervalsPaused) {
        Result withCleanup(OriginalFileDeletionWorker.CleanupReport cleanup) {
            return new Result(action, activitiesDeleted, activitiesProtected, activitiesAmbiguous, sourceAssociationsDeleted,
                    recordsDeleted, lapsDeleted, importFilesDeleted, trainingPlansDeleted, trainingPhasesDeleted,
                    plannedSessionsDeleted, cleanup.deleted(), cleanup.pending(), cleanup.failed(), garminPaused, intervalsPaused);
        }
    }
    private record Pending(Action action, String fingerprint, Instant expiresAt) { }
    private record State(List<DataManagementRepository.ActivityState> activities,
                         List<DataManagementRepository.ActivityState> deletable, int protectedCount, int ambiguousCount,
                         long associations, long records, long laps, long importFiles, long plans, long phases,
                         long sessions, String fingerprint) { }

    public static class DataManagementException extends RuntimeException {
        private final int status;
        private final String code;
        public DataManagementException(int status, String code) { super(code); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
