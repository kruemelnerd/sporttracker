package local.runningdashboard;

import com.garmin.fit.ActivityMesg;
import com.garmin.fit.ActivityMesgListener;
import com.garmin.fit.Decode;
import com.garmin.fit.LapMesg;
import com.garmin.fit.LapMesgListener;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.RecordMesgListener;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SessionMesgListener;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class GarminFitFixtureAdapter {
    private static final String FIXTURE = "fit/synthetic-running.fit";
    private static final double SEMICIRCLES_PER_DEGREE = 2147483648d / 180d;

    public FixtureSummary decodeFixture() {
        try (InputStream input = new ClassPathResource(FIXTURE).getInputStream()) {
            DecodedActivity decoded = decode(input);
            return new FixtureSummary(decoded.sport(), decoded.startTime(), decoded.distanceMeters(), "decoded", decoded.records().size(), decoded.laps().size());
        } catch (Exception exception) {
            throw new FixtureDecodeException(exception);
        }
    }

    public DecodedActivity decode(InputStream input) throws IOException {
        Listener listener = new Listener();
        Decode decode = new Decode();
        MesgBroadcaster broadcaster = new MesgBroadcaster(decode);
        broadcaster.addListener((ActivityMesgListener) listener);
        broadcaster.addListener((RecordMesgListener) listener);
        broadcaster.addListener((LapMesgListener) listener);
        broadcaster.addListener((SessionMesgListener) listener);
        if (!decode.read(input, broadcaster, broadcaster)) {
            throw new IOException("FIT decoder rejected input");
        }
        return listener.decoded();
    }

    static Double degrees(Integer semicircles, boolean latitude) {
        if (semicircles == null) return null;
        double degrees = semicircles / SEMICIRCLES_PER_DEGREE;
        double limit = latitude ? 90d : 180d;
        return Double.isFinite(degrees) && degrees >= -limit && degrees <= limit ? degrees : null;
    }

    public record DecodedActivity(String sport, String subSport, Instant startTime, Double elapsedSeconds,
                                  Double movingSeconds, Double distanceMeters, Double averageSpeedMetersPerSecond,
                                  Integer averageHeartRate, Integer maximumHeartRate, Integer averageCadence, Double ascentMeters,
                                  Integer calories, List<DecodedRecord> records, List<DecodedLap> laps) {
    }

    public record DecodedRecord(Instant timestamp, Double elapsedSeconds, Double distanceMeters, Double latitude,
                                Double longitude, Double altitudeMeters, Double speedMetersPerSecond,
                                Integer heartRate, Integer cadence, Double descentMeters) {
    }

    public record DecodedLap(int lapNumber, Double durationSeconds, Double distanceMeters,
                             Double averageSpeedMetersPerSecond, Integer averageHeartRate,
                             Integer averageCadence, Double ascentMeters, Double descentMeters) {
    }

    private static final class Listener implements ActivityMesgListener, RecordMesgListener, LapMesgListener, SessionMesgListener {
        private final List<DecodedActivity> sessions = new ArrayList<>();
        private final List<DecodedRecord> records = new ArrayList<>();
        private final List<DecodedLap> laps = new ArrayList<>();

        @Override public void onMesg(ActivityMesg mesg) { }

        @Override
        public void onMesg(RecordMesg mesg) {
            Instant timestamp = instant(mesg.getTimestamp());
            records.add(new DecodedRecord(timestamp, null, doubleValue(mesg.getDistance()), degrees(mesg.getPositionLat(), true),
                    degrees(mesg.getPositionLong(), false), doubleValue(mesg.getAltitude()), doubleValue(mesg.getSpeed()),
                    integerValue(mesg.getHeartRate()), integerValue(mesg.getCadence()), null));
        }

        @Override
        public void onMesg(LapMesg mesg) {
            laps.add(new DecodedLap(laps.size() + 1, doubleValue(mesg.getTotalElapsedTime()), doubleValue(mesg.getTotalDistance()),
                    doubleValue(mesg.getAvgSpeed()), integerValue(mesg.getAvgHeartRate()), integerValue(mesg.getAvgCadence()),
                    doubleValue(mesg.getTotalAscent()), doubleValue(mesg.getTotalDescent())));
        }

        @Override
        public void onMesg(SessionMesg mesg) {
            sessions.add(new DecodedActivity(stringValue(mesg.getSport()), stringValue(mesg.getSubSport()), instant(mesg.getStartTime()),
                    doubleValue(mesg.getTotalElapsedTime()), doubleValue(mesg.getTotalTimerTime()), doubleValue(mesg.getTotalDistance()),
                    doubleValue(mesg.getAvgSpeed()), integerValue(mesg.getAvgHeartRate()), integerValue(mesg.getMaxHeartRate()), integerValue(mesg.getAvgRunningCadence() != null ? mesg.getAvgRunningCadence() : mesg.getAvgCadence()),
                    doubleValue(mesg.getTotalAscent()), integerValue(mesg.getTotalCalories()), List.of(), List.of()));
        }

        private DecodedActivity decoded() throws IOException {
            if (sessions.size() != 1) throw new IOException("FIT must contain exactly one session");
            DecodedActivity session = sessions.getFirst();
            if (session.startTime() == null || session.sport() == null) throw new IOException("FIT session is incomplete");
            List<DecodedRecord> ordered = new ArrayList<>(records.size());
            for (DecodedRecord record : records) {
                Double elapsed = record.timestamp() == null ? null : (double) (record.timestamp().toEpochMilli() - session.startTime().toEpochMilli()) / 1000d;
                ordered.add(new DecodedRecord(record.timestamp(), elapsed, record.distanceMeters(), record.latitude(), record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(), record.heartRate(), record.cadence(), null));
            }
            ordered.sort(Comparator.comparing(DecodedRecord::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
            Double previousAltitude = null;
            for (int index = 0; index < ordered.size(); index++) {
                DecodedRecord record = ordered.get(index);
                Double descent = record.altitudeMeters() == null || previousAltitude == null ? null : Math.max(0, previousAltitude - record.altitudeMeters());
                previousAltitude = record.altitudeMeters();
                ordered.set(index, new DecodedRecord(record.timestamp(), record.elapsedSeconds(), record.distanceMeters(), record.latitude(), record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(), record.heartRate(), record.cadence(), descent));
            }
            return new DecodedActivity(session.sport(), session.subSport(), session.startTime(), session.elapsedSeconds(), session.movingSeconds(),
                    session.distanceMeters(), session.averageSpeedMetersPerSecond(), session.averageHeartRate(), session.maximumHeartRate(), session.averageCadence(), session.ascentMeters(),
                    session.calories(), List.copyOf(ordered), List.copyOf(laps));
        }

        private static Instant instant(com.garmin.fit.DateTime value) { return value == null ? null : value.getDate().toInstant(); }
        private static Double doubleValue(Number value) { return value == null ? null : value.doubleValue(); }
        private static Integer integerValue(Number value) { return value == null ? null : value.intValue(); }
        private static String stringValue(Object value) { return value == null ? null : value.toString().toLowerCase(); }
    }
}
