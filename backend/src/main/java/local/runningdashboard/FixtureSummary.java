package local.runningdashboard;

import java.time.Instant;

public record FixtureSummary(
        String sport,
        Instant startTime,
        Double distanceMeters,
        String status,
        int recordCount,
        int lapCount) {
}
