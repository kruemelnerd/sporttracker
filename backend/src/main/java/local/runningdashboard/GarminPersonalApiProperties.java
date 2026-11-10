package local.runningdashboard;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("running-dashboard.garmin-personal-api")
public record GarminPersonalApiProperties(boolean enabled, String apiKey, String baseUrl,
                                          Duration syncInterval, int initialLookbackDays,
                                          int pageSize, int maxPages, int maxResponseBytes,
                                          Duration connectTimeout, Duration responseTimeout) {
    public GarminPersonalApiProperties {
        if (baseUrl == null || baseUrl.isBlank() || syncInterval == null || syncInterval.isNegative() || syncInterval.isZero()
                || initialLookbackDays < 1 || pageSize < 1 || pageSize > 500 || maxPages < 1 || maxPages > 100
                || (long) pageSize * maxPages > Integer.MAX_VALUE || maxResponseBytes < 1 || maxResponseBytes > 104_857_600
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("Garmin API limits and timeouts must be positive");
        }
    }

    public boolean configured() { return enabled && apiKey != null && !apiKey.isBlank(); }
}
