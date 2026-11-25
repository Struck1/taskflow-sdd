plugins {
    java
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.taskflow"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Versions of libraries that Spring Boot does not manage.
val bouncyCastleVersion = "1.87"
val greenMailVersion = "2.1.15"
val playwrightVersion = "1.65.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Argon2 support for Spring Security's Argon2PasswordEncoder (TD-005).
    implementation("org.bouncycastle:bcprov-jdk18on:$bouncyCastleVersion")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.icegreen:greenmail-junit5:$greenMailVersion")
    testImplementation("com.microsoft.playwright:playwright:$playwrightVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Timing tests (NFR-004) depend on machine load, so the normal build never runs them.
tasks.test {
    useJUnitPlatform {
        excludeTags("timing")
    }
}

// Runs only the timing tests. Not wired into `check` or `build` (design §9).
tasks.register<Test>("timingTest") {
    description = "Runs tests tagged 'timing' (NFR-004). Not part of the build."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("timing")
    }
    // There are no timing tests until T17; an empty run must not fail.
    failOnNoDiscoveredTests = false
    shouldRunAfter(tasks.test)
}
