package local.runningdashboard;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("running-dashboard.intervals-icu")
public record IntervalsIcuProperties(boolean enabled, String apiKey, String baseUrl, Duration syncInterval,
                                     int initialLookbackDays) {
    public IntervalsIcuProperties {
        if (syncInterval == null || syncInterval.isNegative() || syncInterval.isZero() || initialLookbackDays < 1) {
            throw new IllegalArgumentException("Intervals.icu sync interval and lookback must be positive");
        }
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("Intervals.icu base URL is required");
    }

    public boolean configured() { return enabled && apiKey != null && !apiKey.isBlank(); }
}
