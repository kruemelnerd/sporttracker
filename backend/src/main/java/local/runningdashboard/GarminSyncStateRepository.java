package local.runningdashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GarminSyncStateRepository {
    private final JdbcTemplate jdbc;

    public GarminSyncStateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SyncState> find() {
        return jdbc.query("SELECT last_attempt_at, last_successful_sync_at, last_successful_authentication_at, retry_after_until, checked_count, imported_count, already_present_count, skipped_count, failed_count, last_error_code FROM garmin_sync_state WHERE id = 1",
                (result, row) -> new SyncState(instant(result.getTimestamp("last_attempt_at")),
                        instant(result.getTimestamp("last_successful_sync_at")),
                        instant(result.getTimestamp("last_successful_authentication_at")),
                        instant(result.getTimestamp("retry_after_until")), result.getInt("checked_count"),
                        result.getInt("imported_count"), result.getInt("already_present_count"),
                        result.getInt("skipped_count"), result.getInt("failed_count"), result.getString("last_error_code")))
                .stream().findFirst();
    }

    public void save(SyncState state) {
        jdbc.update("INSERT INTO garmin_sync_state (id, last_attempt_at, last_successful_sync_at, last_successful_authentication_at, retry_after_until, checked_count, imported_count, already_present_count, skipped_count, failed_count, last_error_code) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET last_attempt_at = EXCLUDED.last_attempt_at, last_successful_sync_at = EXCLUDED.last_successful_sync_at, last_successful_authentication_at = EXCLUDED.last_successful_authentication_at, retry_after_until = EXCLUDED.retry_after_until, checked_count = EXCLUDED.checked_count, imported_count = EXCLUDED.imported_count, already_present_count = EXCLUDED.already_present_count, skipped_count = EXCLUDED.skipped_count, failed_count = EXCLUDED.failed_count, last_error_code = EXCLUDED.last_error_code",
                timestamp(state.lastAttemptAt()), timestamp(state.lastSuccessfulSyncAt()), timestamp(state.lastSuccessfulAuthenticationAt()),
                timestamp(state.retryAfterUntil()), state.checked(), state.imported(), state.alreadyPresent(), state.skipped(), state.failed(), state.lastErrorCode());
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record SyncState(Instant lastAttemptAt, Instant lastSuccessfulSyncAt,
                            Instant lastSuccessfulAuthenticationAt, Instant retryAfterUntil,
                            int checked, int imported, int alreadyPresent, int skipped, int failed,
                            String lastErrorCode) { }
}
