package local.runningdashboard;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnBean(GarminSyncService.class)
@RequestMapping("/api/imports/garmin")
public class GarminSyncController {
    private final GarminSyncService sync;
    private final GarminPersonalApiProperties properties;
    private final IntervalsIcuProperties intervals;
    private final TaskExecutor executor;

    public GarminSyncController(GarminSyncService sync, GarminPersonalApiProperties properties,
                                IntervalsIcuProperties intervals,
                                @Qualifier("garminSyncExecutor") TaskExecutor executor) {
        this.sync = sync;
        this.properties = properties;
        this.intervals = intervals;
        this.executor = executor;
    }

    @GetMapping("/status")
    public GarminSyncService.Status status() { return sync.status(properties.enabled() && intervals.enabled()); }

    @PostMapping("/sync")
    public ResponseEntity<GarminSyncService.Status> synchronize() {
        if (!sync.configured()) return ResponseEntity.status(HttpStatus.CONFLICT).body(status());
        boolean started = sync.startAsync(executor);
        return ResponseEntity.status(started ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(status());
    }
}
