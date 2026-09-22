package local.runningdashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("running-dashboard.import")
public record ImportProperties(int maxBatchCount, long maxFileBytes, long maxRequestBytes) {
    public ImportProperties {
        if (maxBatchCount < 1 || maxFileBytes < 1 || maxRequestBytes < maxFileBytes) {
            throw new IllegalArgumentException("Import limits must be positive and consistent");
        }
    }
}
