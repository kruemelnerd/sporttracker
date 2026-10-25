plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

extra["tomcat.version"] = "11.0.27"
extra["jackson-bom.version"] = "3.1.8"

group = "local.runningdashboard"
version = "0.1.0-SNAPSHOT"

val frontendDir = layout.projectDirectory.dir("../frontend")
val frontendOutput = layout.buildDirectory.dir("generated/frontend")

val npmCi by tasks.registering(Exec::class) {
    workingDir(frontendDir)
    commandLine("npm", "ci")
    inputs.files(frontendDir.file("package.json"), frontendDir.file("package-lock.json"))
    outputs.dir(frontendDir.dir("node_modules"))
}

val typecheckFrontend by tasks.registering(Exec::class) {
    dependsOn(npmCi)
    workingDir(frontendDir)
    commandLine("npm", "run", "typecheck")
    inputs.files(fileTree(frontendDir) {
        exclude("node_modules/**", "dist/**")
    })
}

val frontendBuildOutput = providers.gradleProperty("frontendBuildOutput")

val viteBuildFrontend by tasks.registering(Exec::class) {
    workingDir(frontendDir)
    commandLine("npm", "exec", "vite", "build", "--", "--outDir", frontendOutput.get().asFile.absolutePath, "--emptyOutDir")
    inputs.files(fileTree(frontendDir) {
        exclude("node_modules/**", "dist/**")
    })
    outputs.dir(frontendOutput)
}

val buildFrontend by tasks.registering {
    if (frontendBuildOutput.isPresent) {
        inputs.dir(frontendBuildOutput)
        outputs.dir(frontendOutput)
        doLast {
            val source = file(frontendBuildOutput.get())
            require(source.isDirectory && source.resolve("index.html").isFile) {
                "Frontend build output must be an existing directory containing index.html: $source"
            }
            project.delete(frontendOutput)
            copy {
                from(source)
                into(frontendOutput)
            }
        }
    } else {
        dependsOn(typecheckFrontend, viteBuildFrontend)
    }
}

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
    maxParallelForks = 1
    doFirst {
        val runId = System.getenv("TEST_RUN_ID") ?: throw GradleException("TEST_RUN_ID is required; run tests through scripts/test-all.sh")
        val project = System.getenv("TEST_COMPOSE_PROJECT") ?: throw GradleException("TEST_COMPOSE_PROJECT is required")
        val database = System.getenv("TEST_DATABASE_NAME") ?: throw GradleException("TEST_DATABASE_NAME is required")
        val username = System.getenv("TEST_DATABASE_USERNAME") ?: throw GradleException("TEST_DATABASE_USERNAME is required")
        val password = System.getenv("TEST_DATABASE_PASSWORD") ?: throw GradleException("TEST_DATABASE_PASSWORD is required")
        val port = System.getenv("TEST_DATABASE_PORT") ?: throw GradleException("TEST_DATABASE_PORT is required")
        val databaseUrl = System.getenv("TEST_DATABASE_URL") ?: throw GradleException("TEST_DATABASE_URL is required")
        val dataDirectory = System.getenv("TEST_DATA_DIRECTORY") ?: throw GradleException("TEST_DATA_DIRECTORY is required")
        if (!runId.matches(Regex("[a-f0-9]{24}")) || project != "sporttracker-ci-$runId" ||
            database != "running_dashboard_test_$runId" || username != "running_dashboard_test_$runId" ||
            !port.matches(Regex("[1-9][0-9]{0,4}")) || port.toInt() > 65535 ||
            databaseUrl != "jdbc:postgresql://127.0.0.1:$port/$database" ||
            dataDirectory != "/tmp/running-dashboard-test-$runId") {
            throw GradleException("Refusing test database with inconsistent run identity")
        }
        systemProperty("spring.datasource.url", databaseUrl)
        systemProperty("spring.datasource.username", username)
        systemProperty("spring.datasource.password", password)
        environment("DATABASE_URL", databaseUrl)
        environment("DATABASE_USERNAME", username)
        environment("DATABASE_PASSWORD", password)
        environment("DATA_DIRECTORY", dataDirectory)
        environment("INTERVALS_ICU_ENABLED", "false")
        environment("INTERVALS_ICU_API_KEY", "")
        val marker = "sporttracker-test:$runId:$project"
        val reset = ProcessBuilder("docker", "compose", "-p", project, "-f", "../compose.test.yaml", "exec", "-T", "db",
                "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", username, "-d", database, "-c",
                """DO ${'$'}${'$'} BEGIN
                    IF current_database() <> '$database' OR current_user <> '$username' OR
                       (SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()) <> '$marker' THEN
                        RAISE EXCEPTION 'Refusing to reset database with an unexpected identity';
                    END IF;
                    IF to_regclass('public.activity') IS NOT NULL THEN EXECUTE 'TRUNCATE activity CASCADE'; END IF;
                    IF to_regclass('public.training_plan') IS NOT NULL THEN EXECUTE 'TRUNCATE training_plan CASCADE'; END IF;
                END ${'$'}${'$'};""".trimIndent()).inheritIO().start()
        if (reset.waitFor() != 0) throw GradleException("Refusing to reset backend test database")
    }
}

tasks.named<ProcessResources>("processResources") {
    mustRunAfter(buildFrontend)
    from(frontendOutput) {
        into("static")
    }
    from("src/test/resources/fit/synthetic-running.fit") {
        into("fit")
    }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    dependsOn(buildFrontend)
}
