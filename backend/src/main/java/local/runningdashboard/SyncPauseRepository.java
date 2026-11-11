package local.runningdashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SyncPauseRepository {
    private final JdbcTemplate jdbc;

    public SyncPauseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isPaused(String provider) {
        return Boolean.TRUE.equals(jdbc.query("SELECT paused FROM data_management_sync_pause WHERE provider = ?",
                (result, row) -> result.getBoolean(1), provider).stream().findFirst().orElse(true));
    }

    public void pause(String provider) { setPaused(provider, true); }

    public void resume(String provider) { setPaused(provider, false); }

    private void setPaused(String provider, boolean paused) {
        if (jdbc.update("UPDATE data_management_sync_pause SET paused = ?, changed_at = CURRENT_TIMESTAMP WHERE provider = ?", paused, provider) != 1) {
            throw new IllegalArgumentException("Unknown sync provider");
        }
    }
}
