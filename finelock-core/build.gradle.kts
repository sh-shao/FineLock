plugins { `java-library` }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    api("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
