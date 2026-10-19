package local.runningdashboard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class IntervalsIcuSyncConfiguration {
    @Bean("intervalsIcuSyncExecutor")
    ThreadPoolTaskExecutor intervalsIcuSyncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("intervals-icu-sync-");
        return executor;
    }
}
