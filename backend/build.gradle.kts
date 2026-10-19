plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "local.runningdashboard"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.garmin:fit:21.171.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
    systemProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55432/running_dashboard_e2e")
    systemProperty("spring.datasource.username", "running_dashboard_e2e")
    systemProperty("spring.datasource.password", "running_dashboard_e2e")
    doFirst {
        val reset = ProcessBuilder("docker", "compose", "-p", "sporttracker-m4-e2e", "-f", "../compose.e2e.yaml", "exec", "-T", "db",
                "psql", "-v", "ON_ERROR_STOP=1", "-U", "running_dashboard_e2e", "-d", "running_dashboard_e2e", "-c",
                """DO ${'$'}${'$'} BEGIN
                    IF current_database() <> 'running_dashboard_e2e' OR current_user <> 'running_dashboard_e2e' THEN
                        RAISE EXCEPTION 'Refusing to reset non-test database';
                    END IF;
                    IF to_regclass('public.activity') IS NOT NULL THEN EXECUTE 'TRUNCATE activity CASCADE'; END IF;
                    IF to_regclass('public.training_plan') IS NOT NULL THEN EXECUTE 'TRUNCATE training_plan CASCADE'; END IF;
                END ${'$'}${'$'};""".trimIndent()).inheritIO().start()
        if (reset.waitFor() != 0) throw GradleException("Refusing to reset backend test database")
    }
}

tasks.named<ProcessResources>("processResources") {
    from("src/test/resources/fit/synthetic-running.fit") {
        into("fit")
    }
}
