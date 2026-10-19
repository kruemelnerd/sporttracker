package local.runningdashboard;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class IntervalsIcuActivitySource implements ActivityImportSource {
    private final IntervalsIcuClient client;

    public IntervalsIcuActivitySource(IntervalsIcuClient client) { this.client = client; }

    @Override public String provider() { return "INTERVALS_ICU"; }

    @Override
    public List<RemoteActivity> listActivities(LocalDate oldest, LocalDate newest) {
        return client.listActivities(oldest, newest).stream().map(activity -> new RemoteActivity(activity.id(), activity.type())).toList();
    }

    @Override
    public ActivityFile downloadActivityFile(String externalId) {
        var file = client.downloadActivityFile(externalId);
        return new ActivityFile(file.body(), file.contentType(), file.contentEncoding());
    }
}
