package local.runningdashboard;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(GarminSyncService.class)
public class GarminSyncTriggers {
    private final GarminSyncService sync;
    private final TaskExecutor executor;

    public GarminSyncTriggers(GarminSyncService sync, @Qualifier("garminSyncExecutor") TaskExecutor executor) {
        this.sync = sync;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() { trigger(); }

    @Scheduled(fixedDelayString = "${running-dashboard.garmin-personal-api.sync-interval:PT30M}")
    public void onSchedule() { trigger(); }

    private void trigger() { if (sync.configured()) sync.startAsync(executor); }
}
