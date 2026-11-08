package local.runningdashboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-classpath-only reader for locally downloaded Garmin Personal API FIT files. */
public final class GarminPocFitInspector {
    private GarminPocFitInspector() { }

    public static Summary summarize(GarminFitFixtureAdapter.DecodedActivity activity) {
        List<String> present = new ArrayList<>();
        if (activity.averageSpeedMetersPerSecond() != null) present.add("average_speed");
        if (activity.averageHeartRate() != null || activity.maximumHeartRate() != null) present.add("heart_rate");
        if (activity.averageCadence() != null) present.add("cadence");
        if (activity.ascentMeters() != null) present.add("ascent");
        if (activity.calories() != null) present.add("calories");
        if (activity.records().stream().anyMatch(record -> record.latitude() != null && record.longitude() != null)) present.add("gps");
        if (activity.records().stream().anyMatch(record -> record.heartRate() != null)) present.add("record_heart_rate");
        if (activity.records().stream().anyMatch(record -> record.speedMetersPerSecond() != null)) present.add("record_speed");
        return new Summary(activity.sport(), activity.startTime(), activity.elapsedSeconds(), activity.movingSeconds(),
                activity.distanceMeters(), activity.averageSpeedMetersPerSecond(), activity.averageHeartRate(),
                activity.maximumHeartRate(), activity.averageCadence(), activity.ascentMeters(), activity.calories(),
                activity.records().size(), activity.laps().size(), List.copyOf(present));
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: inspectGarminPocFit -PfitFile=/path/to/activity.fit");
        GarminFitFixtureAdapter.DecodedActivity decoded;
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            decoded = new GarminFitFixtureAdapter().decode(input);
        }
        System.out.println(summarize(decoded));
    }

    public record Summary(String sport, java.time.Instant startTime, Double elapsedSeconds, Double movingSeconds,
                          Double distanceMeters, Double averageSpeedMetersPerSecond, Integer averageHeartRate,
                          Integer maximumHeartRate, Integer averageCadence, Double ascentMeters, Integer calories,
                          int recordCount, int lapCount, List<String> presentFields) { }
}
