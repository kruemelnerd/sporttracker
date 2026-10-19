package local.runningdashboard;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(IntervalsIcuSyncService.class)
@RequestMapping("/api/imports/intervals-icu")
public class IntervalsIcuSyncController {
    private final IntervalsIcuSyncService sync;
    private final TaskExecutor executor;

    public IntervalsIcuSyncController(IntervalsIcuSyncService sync,
                                      @Qualifier("intervalsIcuSyncExecutor") TaskExecutor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    @GetMapping("/status")
    public SyncStatus status() {
        return currentStatus(null);
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncStatus> synchronize() {
        if (!sync.configured()) return ResponseEntity.status(HttpStatus.CONFLICT).body(currentStatus("Intervals.icu is not configured."));
        boolean started = sync.startAsync(executor);
        if (!started) return ResponseEntity.status(HttpStatus.CONFLICT).body(currentStatus("Sync is already running or rate-limited."));
        return ResponseEntity.accepted().body(currentStatus("Sync started."));
    }

    private SyncStatus currentStatus(String message) {
        return new SyncStatus(sync.configured(), sync.running(), sync.lastSuccessfulSyncAt(), sync.lastAttemptAt(),
                sync.retryAfterUntil(), sync.lastResult(), message);
    }

    public record SyncStatus(boolean configured, boolean running, Instant lastSuccessfulSyncAt,
                             Instant lastAttemptAt, Instant retryAfterUntil,
                             IntervalsIcuSyncService.SyncResult lastResult, String message) { }
}
