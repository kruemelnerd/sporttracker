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
    systemProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:55432/running_dashboard_e2e")
    systemProperty("spring.datasource.username", "running_dashboard_e2e")
    systemProperty("spring.datasource.password", "running_dashboard_e2e")
}

tasks.named<ProcessResources>("processResources") {
    from("src/test/resources/fit/synthetic-running.fit") {
        into("fit")
    }
}
