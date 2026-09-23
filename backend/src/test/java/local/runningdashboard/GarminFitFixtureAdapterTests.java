package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class GarminFitFixtureAdapterTests {
    private final GarminFitFixtureAdapter adapter = new GarminFitFixtureAdapter();

    @Test
    void fixtureIntegrityIsStable() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fit/20053968686_ACTIVITY.fit")) {
            assertNotNull(input);
            assertEquals(
                    "fe0710d14f164f033948d3f499ee72886fbf1f11c5193540459cbd37a1d39a67",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
        }
    }

    @Test
    void decodesStableSemanticSummary() {
        FixtureSummary summary = adapter.decodeFixture();

        assertEquals("running", summary.sport());
        assertNotNull(summary.startTime());
        assertTrue(summary.startTime().toString().endsWith("Z"));
        assertNotNull(summary.distanceMeters());
        assertTrue(summary.distanceMeters() > 0);
        assertEquals("decoded", summary.status());
        assertTrue(summary.recordCount() > 0);
        assertTrue(summary.lapCount() > 0);
    }
}
