package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class BuildIdentityTest {
    @Test
    void buildInfoContainsGradleVersionAndCommitField() throws IOException {
        var properties = new Properties();
        try (var input = getClass().getResourceAsStream("/META-INF/build-info.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals("0.1.0-SNAPSHOT", properties.getProperty("build.version"));
        assertNotNull(properties.getProperty("build.commit"));
    }
}
