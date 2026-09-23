package local.runningdashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "spring.flyway.enabled=false",
    "running-dashboard.import.enabled=false"
})
class RunningDashboardApplicationTests {
    @Test
    void applicationContextStartsWithoutDatabase() {
    }
}
