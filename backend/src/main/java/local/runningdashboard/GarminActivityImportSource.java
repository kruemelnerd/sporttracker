package local.runningdashboard;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component("garminActivityImportSource")
public class GarminActivityImportSource implements ActivityImportSource {
    private final GarminPersonalApiClient client;
    private final GarminFitZipExtractor extractor;

    public GarminActivityImportSource(GarminPersonalApiClient client, ImportProperties importProperties,
                                      GarminPersonalApiProperties apiProperties) {
        this.client = client;
        this.extractor = new GarminFitZipExtractor(apiProperties.maxResponseBytes(),
                Math.toIntExact(Math.min(importProperties.maxFileBytes(), Integer.MAX_VALUE - 1L)));
    }

    @Override public String provider() { return "GARMIN"; }

    @Override
    public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
        return client.listActivities(oldest, newest).activities().stream()
                .map(activity -> new RemoteActivity(activity.id(), activity.type())).toList();
    }

    @Override
    public ActivityFile downloadActivityFile(String externalId) {
        var archive = client.downloadActivity(externalId);
        String contentType = archive.contentType() == null ? "" : archive.contentType().toLowerCase(java.util.Locale.ROOT);
        if (!(contentType.startsWith("application/zip") || contentType.startsWith("application/x-zip-compressed")
                || contentType.startsWith("application/octet-stream"))) throw new GarminClientException("INVALID_ARCHIVE");
        byte[] fit = extractor.extract(archive.body());
        return new ActivityFile(fit, "application/octet-stream", null);
    }

    @Override public GarminPersonalApiClient.TrainingMetrics fetchTrainingMetrics(String externalId) {
        return client.activityTrainingMetrics(externalId);
    }
}
