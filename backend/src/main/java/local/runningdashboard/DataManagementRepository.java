package local.runningdashboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataManagementRepository {
    private final JdbcTemplate jdbc;

    public DataManagementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ActivityState> capture(DataManagementService.Action action) {
        List<Long> ids = switch (action) {
            case DELETE_GARMIN -> providerActivityIds("GARMIN");
            case DELETE_INTERVALS_ICU -> providerActivityIds("INTERVALS_ICU");
            case DELETE_ACTIVITIES, RESET_APPLICATION_DATA -> jdbc.query("SELECT id FROM activity ORDER BY id", (r, n) -> r.getLong(1));
        };
        List<ActivityState> states = new ArrayList<>();
        for (long id : ids) {
            List<String> importSources = jdbc.query("SELECT source FROM import_file WHERE activity_id = ? ORDER BY id",
                    (r, n) -> r.getString(1), id);
            List<String> providers = jdbc.query("SELECT provider || ':' || external_id FROM activity_import_source WHERE activity_id = ? ORDER BY provider, external_id",
                    (r, n) -> r.getString(1), id);
            List<String> fileStates = jdbc.query("SELECT id || ':' || sha256 || ':' || COALESCE(source, 'UNKNOWN') || ':' || COALESCE(original_path, '') FROM import_file WHERE activity_id = ? ORDER BY id",
                    (r, n) -> r.getString(1), id);
            List<String> files = jdbc.query("SELECT original_path FROM import_file WHERE activity_id = ? AND original_path IS NOT NULL ORDER BY id",
                    (r, n) -> r.getString(1), id);
            states.add(new ActivityState(id, importSources, providers, files, fileStates,
                    countForActivities("activity_record", List.of(id)), countForActivities("lap", List.of(id))));
        }
        return List.copyOf(states);
    }

    public long providerAssociations(String provider) {
        return jdbc.queryForObject("SELECT count(*) FROM activity_import_source WHERE provider = ?", Long.class, provider);
    }

    public long count(String table) {
        if (!Set.of("activity_record", "lap", "import_file", "training_plan", "training_phase", "planned_session").contains(table)) {
            throw new IllegalArgumentException("Unknown table");
        }
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    public long countForActivities(String table, List<Long> ids) {
        if (!Set.of("activity_record", "lap", "import_file", "activity_import_source").contains(table)) throw new IllegalArgumentException("Unknown table");
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE activity_id IN (" + placeholders + ")", Long.class, ids.toArray());
    }

    public void enqueueFiles(List<String> paths) {
        for (String path : paths) jdbc.update("INSERT INTO pending_file_deletion (relative_path) VALUES (?) ON CONFLICT (relative_path) DO UPDATE SET status = 'PENDING', last_error = NULL, updated_at = CURRENT_TIMESTAMP, completed_at = NULL", path);
    }

    public List<DeletionJob> pendingFileDeletions() {
        return jdbc.query("SELECT id, relative_path FROM pending_file_deletion WHERE status IN ('PENDING', 'FAILED') ORDER BY id",
                (r, n) -> new DeletionJob(r.getLong("id"), r.getString("relative_path")));
    }

    public boolean fileIsReferenced(String path) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM import_file WHERE original_path = ?)", Boolean.class, path);
    }

    public void fileDeletionDone(long id) {
        jdbc.update("UPDATE pending_file_deletion SET status = 'DONE', attempt_count = attempt_count + 1, last_error = NULL, updated_at = CURRENT_TIMESTAMP, completed_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public void fileDeletionFailed(long id, String error) {
        jdbc.update("UPDATE pending_file_deletion SET status = 'FAILED', attempt_count = attempt_count + 1, last_error = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", error, id);
    }

    public long pendingFileDeletionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM pending_file_deletion WHERE status IN ('PENDING', 'FAILED')", Long.class);
    }

    public String planResetFingerprint() {
        return List.of("training_plan", "training_phase", "planned_session").stream()
                .map(table -> table + jdbc.query("SELECT id::text FROM " + table + " ORDER BY id", (r, n) -> r.getString(1)))
                .toList().toString();
    }

    public record DeletionJob(long id, String relativePath) { }

    public int deleteMappings(String provider) { return jdbc.update("DELETE FROM activity_import_source WHERE provider = ?", provider); }
    public int deleteActivities(List<Long> ids) {
        if (ids.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.update("DELETE FROM activity WHERE id IN (" + placeholders + ")", ids.toArray());
    }
    public int deleteAllActivities() { return jdbc.update("DELETE FROM activity"); }
    public int deletePlans() { return jdbc.update("DELETE FROM training_plan"); }
    public int clearGarminSyncState() { return jdbc.update("DELETE FROM garmin_sync_state"); }

    private List<Long> providerActivityIds(String provider) {
        return jdbc.query("SELECT DISTINCT activity_id FROM activity_import_source WHERE provider = ? ORDER BY activity_id",
                (r, n) -> r.getLong(1), provider);
    }

    public record ActivityState(long id, List<String> importSources, List<String> providers, List<String> files,
                                List<String> fileStates, long records, long laps) { }
}
