package local.runningdashboard;

import com.garmin.fit.ActivityMesg;
import com.garmin.fit.ActivityMesgListener;
import com.garmin.fit.Decode;
import com.garmin.fit.Event;
import com.garmin.fit.EventMesg;
import com.garmin.fit.EventMesgListener;
import com.garmin.fit.EventType;
import com.garmin.fit.LapMesg;
import com.garmin.fit.LapMesgListener;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.RecordMesgListener;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SessionMesgListener;
import com.garmin.fit.WorkoutStepMesg;
import com.garmin.fit.WorkoutStepMesgListener;
import com.garmin.fit.WktStepTarget;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        broadcaster.addListener((WorkoutStepMesgListener) listener);
        broadcaster.addListener((EventMesgListener) listener);
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

    static Double preferEnhanced(Number standard, Number enhanced) {
        Number value = enhanced != null ? enhanced : standard;
        return value == null ? null : value.doubleValue();
    }

    static DecodedRecord decodeRecord(RecordMesg mesg) {
        return new DecodedRecord(instant(mesg.getTimestamp()), null, number(mesg.getDistance()),
                degrees(mesg.getPositionLat(), true), degrees(mesg.getPositionLong(), false),
                preferEnhanced(mesg.getAltitude(), mesg.getEnhancedAltitude()),
                preferEnhanced(mesg.getSpeed(), mesg.getEnhancedSpeed()), integer(mesg.getHeartRate()),
                integer(mesg.getCadence()), null,
                bounded(first(scaled(mesg.getStepLength(), 1000d), developerValue(mesg, RecordMesg.StepLengthFieldNum)), 0d, Double.MAX_VALUE),
                bounded(first(scaled(mesg.getVerticalOscillation(), 10d), developerValue(mesg, RecordMesg.VerticalOscillationFieldNum)), 0d, Double.MAX_VALUE),
                bounded(first(number(mesg.getVerticalRatio()), developerValue(mesg, RecordMesg.VerticalRatioFieldNum)), 0d, 100d),
                bounded(first(number(mesg.getStanceTime()), developerValue(mesg, RecordMesg.StanceTimeFieldNum)), 0d, Double.MAX_VALUE),
                bounded(first(number(mesg.getStanceTimeBalance()), developerValue(mesg, RecordMesg.StanceTimeBalanceFieldNum)), 0d, 100d),
                bounded(first(number(mesg.getTemperature()), developerValue(mesg, RecordMesg.TemperatureFieldNum)), -100d, 100d),
                bounded(first(number(mesg.getPower()), developerValue(mesg, RecordMesg.PowerFieldNum)), 0d, Double.MAX_VALUE));
    }

    private static Double developerValue(RecordMesg mesg, int nativeField) {
        String name = switch (nativeField) {
            case RecordMesg.StepLengthFieldNum -> "step_length";
            case RecordMesg.VerticalOscillationFieldNum -> "vertical_oscillation";
            case RecordMesg.VerticalRatioFieldNum -> "vertical_ratio";
            case RecordMesg.StanceTimeFieldNum -> "stance_time";
            case RecordMesg.StanceTimeBalanceFieldNum -> "stance_time_balance";
            case RecordMesg.TemperatureFieldNum -> "temperature";
            case RecordMesg.PowerFieldNum -> "power";
            default -> null;
        };
        if (name == null) return null;
        for (var field : mesg.getDeveloperFields()) {
            String declaredName = field.getName();
            String declaredUnits = field.getUnits();
            String fieldName = declaredName == null ? "" : declaredName.toLowerCase(Locale.ROOT).replace(' ', '_');
            boolean matches = field.getNativeOverride() == nativeField || fieldName.equals(name)
                    || (name.equals("stance_time") && fieldName.equals("ground_contact_time"))
                    || (name.equals("stance_time_balance") && fieldName.equals("ground_contact_balance"))
                    || (name.equals("power") && fieldName.equals("running_power"));
            if (!matches || declaredUnits == null || !field.isValid() || !(field.getValue() instanceof Number value)) continue;
            String units = declaredUnits.trim().toLowerCase(Locale.ROOT);
            double factor = switch (name) {
                case "step_length" -> units.equals("mm") ? 0.001d : units.equals("m") ? 1d : Double.NaN;
                case "vertical_oscillation" -> units.equals("mm") ? 0.1d : units.equals("cm") ? 1d : Double.NaN;
                case "vertical_ratio", "stance_time_balance" -> units.equals("%") ? 1d : Double.NaN;
                case "stance_time" -> units.equals("ms") ? 1d : units.equals("s") ? 1000d : Double.NaN;
                case "temperature" -> units.equals("c") || units.equals("°c") || units.equals("deg c") ? 1d : Double.NaN;
                case "power" -> units.equals("w") || units.equals("watts") ? 1d : Double.NaN;
                default -> Double.NaN;
            };
            Double number = number(value);
            if (number != null && Double.isFinite(factor)) return number * factor;
        }
        return null;
    }

    private static Double first(Double preferred, Double fallback) { return preferred != null ? preferred : fallback; }
    private static Double bounded(Double value, double minimum, double maximum) {
        return value != null && value >= minimum && value <= maximum ? value : null;
    }
    private static Instant instant(com.garmin.fit.DateTime value) { return value == null ? null : value.getDate().toInstant(); }
    private static Double number(Number value) {
        if (value == null) return null;
        double result = value.doubleValue();
        return Double.isFinite(result) ? result : null;
    }
    private static Integer integer(Number value) { return value == null ? null : value.intValue(); }
    private static Double scaled(Number value, double divisor) {
        Double result = number(value);
        return result == null ? null : result / divisor;
    }

    public record DecodedActivity(String sport, String subSport, Instant startTime, Double elapsedSeconds,
                                  Double movingSeconds, Double distanceMeters, Double averageSpeedMetersPerSecond,
                                  Integer averageHeartRate, Integer maximumHeartRate, Integer averageCadence, Double ascentMeters,
                                  Integer calories, List<DecodedRecord> records, List<DecodedLap> laps,
                                  List<DecodedWorkoutStep> workoutSteps, List<DecodedPause> pauses) {
    }

    public record DecodedRecord(Instant timestamp, Double elapsedSeconds, Double distanceMeters, Double latitude,
                                Double longitude, Double altitudeMeters, Double speedMetersPerSecond,
                                Integer heartRate, Integer cadence, Double descentMeters, Double stepLengthMeters,
                                Double verticalOscillationCentimeters, Double verticalRatioPercent,
                                Double groundContactTimeMilliseconds, Double groundContactBalancePercent,
                                Double temperatureCelsius, Double powerWatts) {
    }

    public record DecodedLap(int lapNumber, Double durationSeconds, Double distanceMeters,
                              Double averageSpeedMetersPerSecond, Integer averageHeartRate,
                              Integer averageCadence, Double ascentMeters, Double descentMeters,
                              Integer workoutStepIndex, Integer repetitionNumber,
                              Double startElapsedSeconds, Double endElapsedSeconds,
                              Double startDistanceMeters, Double endDistanceMeters) {
    }

    public record DecodedWorkoutStep(int stepIndex, String name, String durationType,
                                     Double plannedDurationSeconds, Double plannedDistanceMeters,
                                     Integer repeatFromStepIndex, Integer repeatCount, String intensity,
                                     String targetType, Integer targetZone, Double targetLow,
                                     Double targetHigh, String targetUnit) {
    }

    public record DecodedPause(Double startElapsedSeconds, Double endElapsedSeconds) {
    }

    private record TimerEvent(Instant timestamp, EventType type) { }
    private record WorkoutTarget(String type, Integer zone, Double low, Double high, String unit) { }

    private static final class Listener implements ActivityMesgListener, RecordMesgListener, LapMesgListener,
            SessionMesgListener, WorkoutStepMesgListener, EventMesgListener {
        private final List<DecodedActivity> sessions = new ArrayList<>();
        private final List<DecodedRecord> records = new ArrayList<>();
        private final List<LapMesg> sourceLaps = new ArrayList<>();
        private final List<DecodedWorkoutStep> workoutSteps = new ArrayList<>();
        private final List<TimerEvent> timerEvents = new ArrayList<>();

        @Override public void onMesg(ActivityMesg mesg) { }

        @Override
        public void onMesg(RecordMesg mesg) {
            records.add(decodeRecord(mesg));
        }

        @Override
        public void onMesg(LapMesg mesg) {
            sourceLaps.add(mesg);
        }

        @Override
        public void onMesg(WorkoutStepMesg mesg) {
            if (mesg.getMessageIndex() == null) return;
            String durationType = stringValue(mesg.getDurationType());
            Double duration = mesg.getDurationType() == com.garmin.fit.WktStepDuration.TIME
                    ? doubleValue(mesg.getDurationTime()) : null;
            Double distance = mesg.getDurationType() == com.garmin.fit.WktStepDuration.DISTANCE
                    ? doubleValue(mesg.getDurationDistance()) : null;
            Integer repeatFrom = mesg.getDurationType() == com.garmin.fit.WktStepDuration.REPEAT_UNTIL_STEPS_CMPLT
                    && mesg.getDurationStep() != null ? mesg.getDurationStep().intValue() : null;
            Integer repeatCount = repeatFrom != null && mesg.getRepeatSteps() != null
                    ? mesg.getRepeatSteps().intValue() : null;
            WorkoutTarget target = workoutTarget(mesg);
            workoutSteps.add(new DecodedWorkoutStep(mesg.getMessageIndex(), mesg.getWktStepName(), durationType,
                    duration, distance, repeatFrom, repeatCount, stringValue(mesg.getIntensity()),
                    target.type(), target.zone(), target.low(), target.high(), target.unit()));
        }

        private static WorkoutTarget workoutTarget(WorkoutStepMesg mesg) {
            WktStepTarget type = mesg.getTargetType();
            if (type == null || type == WktStepTarget.OPEN || type == WktStepTarget.INVALID) {
                return new WorkoutTarget(null, null, null, null, null);
            }
            return switch (type) {
                case HEART_RATE, HEART_RATE_LAP -> offsetTarget("heart_rate", mesg.getTargetHrZone(),
                        mesg.getCustomTargetHeartRateLow(), mesg.getCustomTargetHeartRateHigh(), 100, "bpm", "% HRmax");
                case POWER, POWER_3S, POWER_10S, POWER_30S, POWER_LAP -> offsetTarget("power", mesg.getTargetPowerZone(),
                        mesg.getCustomTargetPowerLow(), mesg.getCustomTargetPowerHigh(), 1000, "W", "% FTP");
                case SPEED, SPEED_LAP -> new WorkoutTarget("speed", integer(mesg.getTargetSpeedZone()),
                        number(mesg.getCustomTargetSpeedLow()), number(mesg.getCustomTargetSpeedHigh()), "m/s");
                case CADENCE -> new WorkoutTarget("cadence", integer(mesg.getTargetCadenceZone()),
                        number(mesg.getCustomTargetCadenceLow()), number(mesg.getCustomTargetCadenceHigh()), "steps/min");
                default -> new WorkoutTarget(stringValue(type), null, null, null, null);
            };
        }

        private static WorkoutTarget offsetTarget(String type, Long zone, Long low, Long high,
                                                  long offset, String absoluteUnit, String relativeUnit) {
            boolean lowAbsolute = low != null && low > offset;
            boolean highAbsolute = high != null && high > offset;
            if (low != null && high != null && lowAbsolute != highAbsolute) {
                return new WorkoutTarget(type, integer(zone), null, null, null);
            }
            boolean absolute = low != null ? lowAbsolute : highAbsolute;
            double correction = absolute ? offset : 0d;
            return new WorkoutTarget(type, integer(zone),
                    low == null ? null : low - correction, high == null ? null : high - correction,
                    absolute ? absoluteUnit : relativeUnit);
        }

        @Override
        public void onMesg(EventMesg mesg) {
            if (mesg.getEvent() == Event.TIMER && mesg.getTimestamp() != null && mesg.getEventType() != null) {
                timerEvents.add(new TimerEvent(instant(mesg.getTimestamp()), mesg.getEventType()));
            }
        }

        @Override
        public void onMesg(SessionMesg mesg) {
            sessions.add(new DecodedActivity(stringValue(mesg.getSport()), stringValue(mesg.getSubSport()), instant(mesg.getStartTime()),
                    doubleValue(mesg.getTotalElapsedTime()), doubleValue(mesg.getTotalTimerTime()), doubleValue(mesg.getTotalDistance()),
                    doubleValue(mesg.getAvgSpeed()), integerValue(mesg.getAvgHeartRate()), integerValue(mesg.getMaxHeartRate()), integerValue(mesg.getAvgRunningCadence() != null ? mesg.getAvgRunningCadence() : mesg.getAvgCadence()),
                    doubleValue(mesg.getTotalAscent()), integerValue(mesg.getTotalCalories()), List.of(), List.of(),
                    List.of(), List.of()));
        }

        private DecodedActivity decoded() throws IOException {
            if (sessions.size() != 1) throw new IOException("FIT must contain exactly one session");
            DecodedActivity session = sessions.getFirst();
            if (session.startTime() == null || session.sport() == null) throw new IOException("FIT session is incomplete");
            List<DecodedRecord> ordered = new ArrayList<>(records.size());
            for (DecodedRecord record : records) {
                Double elapsed = record.timestamp() == null ? null : (double) (record.timestamp().toEpochMilli() - session.startTime().toEpochMilli()) / 1000d;
                ordered.add(new DecodedRecord(record.timestamp(), elapsed, record.distanceMeters(), record.latitude(),
                        record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(), record.heartRate(),
                        record.cadence(), null, record.stepLengthMeters(), record.verticalOscillationCentimeters(),
                        record.verticalRatioPercent(), record.groundContactTimeMilliseconds(),
                        record.groundContactBalancePercent(), record.temperatureCelsius(), record.powerWatts()));
            }
            ordered.sort(Comparator.comparing(DecodedRecord::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
            workoutSteps.sort(Comparator.comparing(DecodedWorkoutStep::stepIndex));
            List<DecodedLap> laps = decodeLaps(session.startTime());
            Double previousAltitude = null;
            for (int index = 0; index < ordered.size(); index++) {
                DecodedRecord record = ordered.get(index);
                Double descent = record.altitudeMeters() == null || previousAltitude == null ? null : Math.max(0, previousAltitude - record.altitudeMeters());
                previousAltitude = record.altitudeMeters();
                ordered.set(index, new DecodedRecord(record.timestamp(), record.elapsedSeconds(), record.distanceMeters(),
                        record.latitude(), record.longitude(), record.altitudeMeters(), record.speedMetersPerSecond(),
                        record.heartRate(), record.cadence(), descent, record.stepLengthMeters(),
                        record.verticalOscillationCentimeters(), record.verticalRatioPercent(),
                        record.groundContactTimeMilliseconds(), record.groundContactBalancePercent(),
                        record.temperatureCelsius(), record.powerWatts()));
            }
            return new DecodedActivity(session.sport(), session.subSport(), session.startTime(), session.elapsedSeconds(), session.movingSeconds(),
                    session.distanceMeters(), session.averageSpeedMetersPerSecond(), session.averageHeartRate(), session.maximumHeartRate(), session.averageCadence(), session.ascentMeters(),
                    session.calories(), List.copyOf(ordered), List.copyOf(laps), List.copyOf(workoutSteps),
                    pauses(session.startTime()));
        }

        private List<DecodedLap> decodeLaps(Instant activityStart) {
            List<DecodedLap> result = new ArrayList<>(sourceLaps.size());
            Double accumulatedDistance = 0d;
            boolean completeDistance = true;
            for (LapMesg mesg : sourceLaps) {
                Double lapDistance = number(mesg.getTotalDistance());
                Double startDistance = completeDistance ? accumulatedDistance : null;
                if (lapDistance == null || lapDistance < 0) completeDistance = false;
                else if (completeDistance) accumulatedDistance += lapDistance;
                result.add(new DecodedLap(result.size() + 1, number(mesg.getTotalElapsedTime()), lapDistance,
                        number(mesg.getAvgSpeed()), integer(mesg.getAvgHeartRate()), integer(mesg.getAvgCadence()),
                        number(mesg.getTotalAscent()), number(mesg.getTotalDescent()), mesg.getWktStepIndex(),
                        mesg.getRepetitionNum(), secondsBetween(activityStart, instant(mesg.getStartTime())),
                        secondsBetween(activityStart, instant(mesg.getTimestamp())), startDistance,
                        completeDistance ? accumulatedDistance : null));
            }
            return result;
        }

        private List<DecodedPause> pauses(Instant activityStart) {
            List<DecodedPause> pauses = new ArrayList<>();
            Instant stoppedAt = null;
            for (TimerEvent event : timerEvents.stream()
                    .sorted(Comparator.comparing(TimerEvent::timestamp)).toList()) {
                if (event.type() == EventType.STOP || event.type() == EventType.STOP_ALL
                        || event.type() == EventType.STOP_DISABLE || event.type() == EventType.STOP_DISABLE_ALL) {
                    if (stoppedAt == null) stoppedAt = event.timestamp();
                } else if (event.type() == EventType.START && stoppedAt != null) {
                    if (event.timestamp().isAfter(stoppedAt)) {
                        pauses.add(new DecodedPause(secondsBetween(activityStart, stoppedAt),
                                secondsBetween(activityStart, event.timestamp())));
                    }
                    stoppedAt = null;
                }
            }
            return List.copyOf(pauses);
        }

        private static Double secondsBetween(Instant start, Instant end) {
            return start == null || end == null ? null : (end.toEpochMilli() - start.toEpochMilli()) / 1000d;
        }

        private static Instant instant(com.garmin.fit.DateTime value) { return value == null ? null : value.getDate().toInstant(); }
        private static Double doubleValue(Number value) { return value == null ? null : value.doubleValue(); }
        private static Integer integerValue(Number value) { return value == null ? null : value.intValue(); }
        private static String stringValue(Object value) { return value == null ? null : value.toString().toLowerCase(); }
    }
}
