package local.runningdashboard;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ActivityImportSourceRepository {
    private final JdbcTemplate jdbc;

    public ActivityImportSourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Long> findActivityId(String provider, String externalId) {
        return jdbc.query("SELECT activity_id FROM activity_import_source WHERE provider = ? AND external_id = ?",
                (result, row) -> result.getLong(1), provider, externalId).stream().findFirst();
    }

    public boolean link(String provider, String externalId, long activityId) {
        return jdbc.update("INSERT INTO activity_import_source (provider, external_id, activity_id) VALUES (?, ?, ?) ON CONFLICT (provider, external_id) DO NOTHING",
                provider, externalId, activityId) == 1;
    }
}
