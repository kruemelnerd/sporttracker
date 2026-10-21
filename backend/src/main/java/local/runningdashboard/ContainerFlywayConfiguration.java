package local.runningdashboard;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("container")
class ContainerFlywayConfiguration {
    @Bean
    @DependsOn("containerDatabaseConfiguration")
    FlywayMigrationStrategy containerFlywayMigrationStrategy() {
        return flyway -> flyway.migrate();
    }
}
