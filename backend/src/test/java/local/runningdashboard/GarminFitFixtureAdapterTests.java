package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.garmin.fit.DateTime;
import com.garmin.fit.DeveloperDataIdMesg;
import com.garmin.fit.DeveloperField;
import com.garmin.fit.Event;
import com.garmin.fit.EventMesg;
import com.garmin.fit.EventType;
import com.garmin.fit.File;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FieldDescriptionMesg;
import com.garmin.fit.Intensity;
import com.garmin.fit.LapMesg;
import com.garmin.fit.LapTrigger;
import com.garmin.fit.RecordMesg;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.Sport;
import com.garmin.fit.WktStepDuration;
import com.garmin.fit.WktStepTarget;
import com.garmin.fit.WorkoutMesg;
import com.garmin.fit.WorkoutStepMesg;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GarminFitFixtureAdapterTests {
    private final GarminFitFixtureAdapter adapter = new GarminFitFixtureAdapter();

    @Test
    void fixtureIsPresent() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fit/synthetic-running.fit")) {
            assertNotNull(input);
        }
    }

    @Test
    void decodesStableSemanticSummary() {
        FixtureSummary summary = adapter.decodeFixture();

        assertEquals("running", summary.sport());
        assertEquals(java.time.Instant.parse("2024-01-01T12:00:00Z"), summary.startTime());
        assertEquals(5000d, summary.distanceMeters());
        assertEquals("decoded", summary.status());
        assertEquals(2, summary.recordCount());
        assertEquals(1, summary.lapCount());
    }

    @Test
    void prefersEnhancedFitAltitudeAndSpeedWhenPresent() {
        assertEquals(123.5d, GarminFitFixtureAdapter.preferEnhanced(100d, 123.5f));
        assertEquals(2.5d, GarminFitFixtureAdapter.preferEnhanced(2.5f, null));
    }

    @Test
    void mapsNativeRunningDynamicsAndOptionalRecordMetricsToCanonicalUnits() {
        RecordMesg mesg = new RecordMesg();
        mesg.setStepLength(1234f);
        mesg.setVerticalOscillation(78f);
        mesg.setVerticalRatio(8.3f);
        mesg.setStanceTime(245.5f);
        mesg.setStanceTimeBalance(49.7f);
        mesg.setTemperature((byte) 12);
        mesg.setPower(250);
        mesg.setCadence((short) 88);

        var record = GarminFitFixtureAdapter.decodeRecord(mesg);

        assertEquals(1.234d, record.stepLengthMeters(), 0.0001d);
        assertEquals(7.8d, record.verticalOscillationCentimeters(), 0.0001d);
        assertEquals(8.3d, record.verticalRatioPercent(), 0.0001d);
        assertEquals(245.5d, record.groundContactTimeMilliseconds(), 0.0001d);
        assertEquals(49.7d, record.groundContactBalancePercent(), 0.0001d);
        assertEquals(12d, record.temperatureCelsius(), 0.0001d);
        assertEquals(250d, record.powerWatts(), 0.0001d);
        assertEquals(88, record.cadence());
    }

    @Test
    void mapsAbsentRunningDynamicsAsNullRatherThanZero() {
        var record = GarminFitFixtureAdapter.decodeRecord(new RecordMesg());

        assertNull(record.stepLengthMeters());
        assertNull(record.verticalOscillationCentimeters());
        assertNull(record.verticalRatioPercent());
        assertNull(record.groundContactTimeMilliseconds());
        assertNull(record.groundContactBalancePercent());
        assertNull(record.temperatureCelsius());
        assertNull(record.powerWatts());
    }

    @Test
    void mapsDeveloperRunningDynamicsOnlyWhenNameAndUnitsAreRecognized() {
        RecordMesg mesg = new RecordMesg();
        mesg.addDeveloperField(developerField("step_length", "mm", 1234f, null, (short) 0));
        mesg.addDeveloperField(developerField("vertical_oscillation", "cm", 7.5f, null, (short) 1));
        mesg.addDeveloperField(developerField("ground_contact_time", "s", 0.24f, null, (short) 2));
        mesg.addDeveloperField(developerField("vertical_ratio", "strides", 8f, null, (short) 3));
        mesg.addDeveloperField(developerField("vertical_ratio", "%", 101f, null, (short) 4));

        var record = GarminFitFixtureAdapter.decodeRecord(mesg);

        assertEquals(1.234d, record.stepLengthMeters(), 0.0001d);
        assertEquals(7.5d, record.verticalOscillationCentimeters(), 0.0001d);
        assertEquals(240d, record.groundContactTimeMilliseconds(), 0.0001d);
        assertNull(record.verticalRatioPercent());
    }

    @Test
    void usesDeveloperNativeOverrideWhenFieldNameIsVendorSpecific() {
        RecordMesg mesg = new RecordMesg();
        mesg.addDeveloperField(developerField("stride_mm", "mm", 1500f,
                (short) RecordMesg.StepLengthFieldNum, (short) 0));

        assertEquals(1.5d, GarminFitFixtureAdapter.decodeRecord(mesg).stepLengthMeters(), 0.0001d);
    }

    @Test
    void decodesWorkoutStepsRepeatedLapLinksAndTimerPauseFromFitMessages() throws Exception {
        GarminFitFixtureAdapter.DecodedActivity decoded;
        try (InputStream input = workoutFit()) {
            decoded = adapter.decode(input);
        }

        assertEquals(List.of(0, 1, 2, 3), decoded.workoutSteps().stream().map(GarminFitFixtureAdapter.DecodedWorkoutStep::stepIndex).toList());
        assertEquals(10d, decoded.workoutSteps().get(0).plannedDurationSeconds());
        assertEquals(400d, decoded.workoutSteps().get(1).plannedDistanceMeters());
        assertEquals("Warmup", decoded.workoutSteps().get(0).name());
        assertEquals("warmup", decoded.workoutSteps().get(0).intensity());
        assertEquals("active", decoded.workoutSteps().get(1).intensity());
        assertEquals("repeat_until_steps_cmplt", decoded.workoutSteps().get(2).durationType());
        assertEquals(1, decoded.workoutSteps().get(2).repeatFromStepIndex());
        assertEquals(2, decoded.workoutSteps().get(2).repeatCount());
        assertEquals("heart_rate", decoded.workoutSteps().get(1).targetType());
        assertEquals(135d, decoded.workoutSteps().get(1).targetLow());
        assertEquals(155d, decoded.workoutSteps().get(1).targetHigh());
        assertEquals("bpm", decoded.workoutSteps().get(1).targetUnit());
        assertEquals(List.of(0, 1, 2, 1), decoded.laps().stream().map(GarminFitFixtureAdapter.DecodedLap::workoutStepIndex).toList());
        assertEquals(List.of(1, 2), decoded.laps().stream().map(GarminFitFixtureAdapter.DecodedLap::repetitionNumber).filter(java.util.Objects::nonNull).toList());
        assertEquals(0d, decoded.laps().get(0).startElapsedSeconds());
        assertEquals(10d, decoded.laps().get(0).endElapsedSeconds());
        assertEquals(0d, decoded.laps().get(0).startDistanceMeters());
        assertEquals(500d, decoded.laps().get(0).endDistanceMeters());
        assertEquals(1, decoded.pauses().size());
        assertEquals(12d, decoded.pauses().getFirst().startElapsedSeconds());
        assertEquals(15d, decoded.pauses().getFirst().endElapsedSeconds());
    }

    @Test
    void existingActivitiesWithoutWorkoutMessagesRemainUnstructured() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fit/synthetic-running.fit")) {
            var decoded = adapter.decode(input);

            assertTrue(decoded.workoutSteps().isEmpty());
            assertTrue(decoded.pauses().isEmpty());
            assertTrue(decoded.laps().stream().allMatch(lap -> lap.workoutStepIndex() == null));
        }
    }

    private static DeveloperField developerField(String name, String units, float value, Short nativeField,
                                                 short fieldNumber) {
        DeveloperDataIdMesg id = new DeveloperDataIdMesg();
        id.setDeveloperDataIndex((short) 0);
        FieldDescriptionMesg description = new FieldDescriptionMesg();
        description.setDeveloperDataIndex((short) 0);
        description.setFieldDefinitionNumber(fieldNumber);
        description.setFitBaseTypeId((short) 136);
        description.setFieldName(0, name);
        description.setUnits(0, units);
        description.setNativeFieldNum(nativeField);
        DeveloperField field = new DeveloperField(description, id);
        field.setValue(value);
        return field;
    }

    private static InputStream workoutFit() throws Exception {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        List<com.garmin.fit.Mesg> messages = new ArrayList<>();
        FileIdMesg fileId = new FileIdMesg();
        fileId.setType(File.ACTIVITY);
        fileId.setManufacturer(1);
        fileId.setTimeCreated(new DateTime(start));
        messages.add(fileId);

        WorkoutMesg workout = new WorkoutMesg();
        workout.setSport(Sport.RUNNING);
        workout.setWktName("Synthetic intervals");
        workout.setNumValidSteps(4);
        messages.add(workout);
        messages.add(workoutStep(0, "Warmup", WktStepDuration.TIME, 10, null, Intensity.WARMUP));
        WorkoutStepMesg fast = workoutStep(1, "Fast", WktStepDuration.DISTANCE, null, 400, Intensity.ACTIVE);
        fast.setTargetType(WktStepTarget.HEART_RATE);
        fast.setTargetValue(0L);
        fast.setCustomTargetHeartRateLow(235L);
        fast.setCustomTargetHeartRateHigh(255L);
        messages.add(fast);
        WorkoutStepMesg repeat = new WorkoutStepMesg();
        repeat.setMessageIndex(2);
        repeat.setDurationType(WktStepDuration.REPEAT_UNTIL_STEPS_CMPLT);
        repeat.setDurationStep(1L);
        repeat.setTargetType(WktStepTarget.OPEN);
        repeat.setRepeatSteps(2L);
        messages.add(repeat);
        messages.add(workoutStep(3, "Cool down", WktStepDuration.TIME, 5, null, Intensity.COOLDOWN));

        messages.add(timerEvent(start, 0, EventType.START));
        messages.add(timerEvent(start, 12, EventType.STOP));
        messages.add(timerEvent(start, 15, EventType.START));
        messages.add(timerEvent(start, 30, EventType.STOP_ALL));
        messages.add(lap(start, 0, 10, 500, 0, null));
        messages.add(lap(start, 10, 20, 400, 1, 1));
        messages.add(lap(start, 20, 25, 100, 2, null));
        messages.add(lap(start, 25, 30, 500, 1, 2));
        SessionMesg session = new SessionMesg();
        session.setSport(Sport.RUNNING);
        session.setStartTime(new DateTime(start));
        session.setTimestamp(new DateTime(start.plusSeconds(30)));
        session.setTotalElapsedTime(30f);
        session.setTotalTimerTime(27f);
        session.setTotalDistance(1500f);
        messages.add(session);

        Path file = Files.createTempFile("sporttracker-workout-", ".fit");
        FileEncoder encoder = new FileEncoder(file.toFile());
        try {
            encoder.write(messages);
        } finally {
            encoder.close();
        }
        return new java.io.FilterInputStream(Files.newInputStream(file)) {
            @Override public void close() throws java.io.IOException {
                try { super.close(); } finally { Files.deleteIfExists(file); }
            }
        };
    }

    private static WorkoutStepMesg workoutStep(int index, String name, WktStepDuration durationType,
                                              Integer seconds, Integer meters, Intensity intensity) {
        WorkoutStepMesg step = new WorkoutStepMesg();
        step.setMessageIndex(index);
        step.setWktStepName(name);
        step.setDurationType(durationType);
        if (seconds != null) step.setDurationTime(seconds.floatValue());
        if (meters != null) step.setDurationDistance(meters.floatValue());
        step.setTargetType(WktStepTarget.OPEN);
        step.setIntensity(intensity);
        return step;
    }

    private static LapMesg lap(Instant start, int startSeconds, int endSeconds, int meters,
                               int stepIndex, Integer repetition) {
        LapMesg lap = new LapMesg();
        lap.setEvent(Event.LAP);
        lap.setEventType(EventType.STOP);
        lap.setStartTime(new DateTime(start.plusSeconds(startSeconds)));
        lap.setTimestamp(new DateTime(start.plusSeconds(endSeconds)));
        lap.setTotalElapsedTime((float) (endSeconds - startSeconds));
        lap.setTotalTimerTime((float) (endSeconds - startSeconds));
        lap.setTotalDistance((float) meters);
        lap.setLapTrigger(LapTrigger.MANUAL);
        lap.setWktStepIndex(stepIndex);
        lap.setRepetitionNum(repetition);
        return lap;
    }

    private static EventMesg timerEvent(Instant start, int elapsedSeconds, EventType type) {
        EventMesg event = new EventMesg();
        event.setEvent(Event.TIMER);
        event.setEventType(type);
        event.setTimestamp(new DateTime(start.plusSeconds(elapsedSeconds)));
        return event;
    }
}
