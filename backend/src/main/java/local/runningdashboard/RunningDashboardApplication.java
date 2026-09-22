package local.runningdashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RunningDashboardApplication {
    public static void main(String[] args) {
        SpringApplication.run(RunningDashboardApplication.class, args);
    }
}
