package local.runningdashboard;

import java.time.LocalDate;
import java.util.List;

public interface ActivityImportSource {
    String provider();
    List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest);
    ActivityFile downloadActivityFile(String externalId);
    default GarminPersonalApiClient.TrainingMetrics fetchTrainingMetrics(String externalId) { return null; }

    record RemoteActivity(String id, String type) { }
    record ActivityFile(byte[] body, String contentType, String contentEncoding) { }
}
