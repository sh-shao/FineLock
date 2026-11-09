plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":finelock-core"))
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.finelock"
        name = "FineLock"
        version = project.version.toString()
        ideaVersion { sinceBuild = "253" }
    }
    buildSearchableOptions = false
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // open the demo project in the sandbox IDE
    args(rootProject.file("examples/demo").absolutePath)
}
