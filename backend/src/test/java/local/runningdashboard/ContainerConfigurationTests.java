package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ContainerConfigurationTests {
    @TempDir java.nio.file.Path secrets;

    @Test
    void containerProfileSetsServerAndDataDirectoryWithoutChangingDefaultProfile() {
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().getPropertySources().remove("systemProperties"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=container", "DATABASE_URL=jdbc:postgresql://db/test",
                        "DATABASE_USERNAME=test-user", "DATABASE_PASSWORD=synthetic-test-secret")
                .run(context -> {
                    assertTrue(context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("container")));
                    assertEquals("0.0.0.0", context.getEnvironment().getProperty("server.address"));
                    assertEquals("8080", context.getEnvironment().getProperty("server.port"));
                    assertEquals("/data", context.getEnvironment().getProperty("running-dashboard.data-directory"));
                    assertEquals("jdbc:postgresql://db/test", context.getEnvironment().getProperty("spring.datasource.url"));
                    assertEquals("test-user", context.getEnvironment().getProperty("spring.datasource.username"));
                    assertEquals("synthetic-test-secret", context.getEnvironment().getProperty("spring.datasource.password"));
                    assertEquals("false", context.getEnvironment().getProperty("running-dashboard.intervals-icu.enabled"));
                    assertEquals("true", context.getEnvironment().getProperty("spring.flyway.clean-disabled"));
                    assertEquals("true", context.getEnvironment().getProperty("spring.flyway.validate-on-migrate"));
                    assertEquals("false", context.getEnvironment().getProperty("spring.flyway.baseline-on-migrate"));
                });

        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().getPropertySources().remove("systemProperties"))
                .withInitializer(new ConfigDataApplicationContextInitializer()).run(context ->
                assertFalse(context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("container"))));
    }

    @Test
    void missingRequiredContainerDatabaseConfigurationFailsStartupBeforeFlyway() {
        new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().getPropertySources().remove("systemProperties"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ContainerProfileConfiguration.class)
                .withPropertyValues("spring.profiles.active=container")
                .run(context -> assertTrue(context.getStartupFailure() != null));
    }

    @Test
    void blankRequiredContainerDatabaseSecretsAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> new ContainerDatabaseConfiguration("jdbc:postgresql://db/app", "app", "  "));
    }

    @Test
    void configtreeReadsSyntheticDatabaseAndOptionalIntervalsSecrets() throws Exception {
        Files.writeString(secrets.resolve("DATABASE_PASSWORD"), "synthetic-db-secret");
        Files.writeString(secrets.resolve("INTERVALS_ICU_API_KEY"), "synthetic-intervals-key");
        var source = new ConfigTreePropertySource("test-secrets", secrets);
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(source);

        assertEquals("synthetic-db-secret", environment.getProperty("DATABASE_PASSWORD"));
        assertEquals("synthetic-intervals-key", environment.getProperty("INTERVALS_ICU_API_KEY"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ContainerDatabaseConfiguration.class, ContainerFlywayConfiguration.class})
    static class ContainerProfileConfiguration { }
}
