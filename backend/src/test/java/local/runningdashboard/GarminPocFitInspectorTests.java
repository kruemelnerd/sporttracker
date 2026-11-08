package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class GarminPocFitInspectorTests {
    @Test
    void inspectsFitThroughTheExistingDecoderWithoutImportingIt() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fit/synthetic-running.fit")) {
            var decoded = new GarminFitFixtureAdapter().decode(input);
            var summary = GarminPocFitInspector.summarize(decoded);

            assertEquals("running", summary.sport());
            assertEquals(5000d, summary.distanceMeters());
            assertEquals(2, summary.recordCount());
            assertEquals(1, summary.lapCount());
        }
    }
}
