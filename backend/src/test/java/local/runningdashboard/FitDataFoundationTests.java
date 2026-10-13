package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;

class FitDataFoundationTests {
    @Test
    void exposesOptionalMaximumHeartRateWhenFixtureContainsIt() throws Exception {
        GarminFitFixtureAdapter adapter = new GarminFitFixtureAdapter();
        GarminFitFixtureAdapter.DecodedActivity activity;
        try (InputStream input = new ClassPathResource("fit/synthetic-running.fit").getInputStream()) {
            activity = adapter.decode(input);
        }

        assertNotNull(activity.maximumHeartRate());
        assertTrue(activity.maximumHeartRate() > 0);
        assertEquals(150, activity.maximumHeartRate());
    }
}
