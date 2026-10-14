package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
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
}
