package local.runningdashboard;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(IntervalsIcuSyncService.class)
public class IntervalsIcuSyncTriggers {
    private final IntervalsIcuSyncService sync;
    private final TaskExecutor executor;
    private final GarminPersonalApiProperties garmin;

    public IntervalsIcuSyncTriggers(IntervalsIcuSyncService sync,
                                    @Qualifier("intervalsIcuSyncExecutor") TaskExecutor executor,
                                    GarminPersonalApiProperties garmin) {
        this.sync = sync;
        this.executor = executor;
        this.garmin = garmin;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() { trigger(); }

    @Scheduled(fixedDelayString = "${running-dashboard.intervals-icu.sync-interval:PT15M}")
    public void onSchedule() { trigger(); }

    private void trigger() {
        if (sync.configured() && !automaticSyncSuppressed()) sync.startAsync(executor);
    }

    public boolean automaticSyncSuppressed() { return garmin.configured() && sync.configured(); }
}
