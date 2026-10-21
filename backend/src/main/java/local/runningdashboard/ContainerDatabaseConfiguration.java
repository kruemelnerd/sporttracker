package local.runningdashboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("container")
class ContainerDatabaseConfiguration {
    ContainerDatabaseConfiguration(@Value("${DATABASE_URL}") String url,
                                   @Value("${DATABASE_USERNAME}") String username,
                                   @Value("${DATABASE_PASSWORD}") String password) {
        if (url == null || url.isBlank() || username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Container database configuration is required");
        }
    }
}
