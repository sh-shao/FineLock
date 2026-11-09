plugins { application }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies { implementation(project(":finelock-core")) }

application {
    mainClass = "finelock.cli.Main"
    applicationName = "finelock"
}
