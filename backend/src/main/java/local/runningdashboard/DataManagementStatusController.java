package local.runningdashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!container & !production & !prod")
@Lazy
@RequestMapping("/api/data-management/status")
public class DataManagementStatusController {
    private final boolean enabled;
    private final SyncPauseRepository pauses;
    private final DataManagementRepository repository;

    public DataManagementStatusController(@Value("${running-dashboard.data-management.enabled:false}") boolean enabled,
                                          SyncPauseRepository pauses, DataManagementRepository repository) {
        this.enabled = enabled;
        this.pauses = pauses;
        this.repository = repository;
    }

    @GetMapping
    public Status status() {
        return new Status(enabled, pauses.isPaused("GARMIN"), pauses.isPaused("INTERVALS_ICU"), repository.pendingFileDeletionCount());
    }

    public record Status(boolean enabled, boolean garminPaused, boolean intervalsPaused, long pendingFileDeletions) { }
}
