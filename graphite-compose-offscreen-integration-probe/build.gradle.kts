plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.13.0-alpha01"
    application
}

group = "org.jetbrains.lets-plot.probe"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

val graphiteSkikoVersion = providers.gradleProperty("graphiteSkikoVersion").orElse("0.153.0")

dependencies {
    implementation(compose.desktop.currentOs)

    implementation("org.jetbrains.skiko:skiko:${graphiteSkikoVersion.get()}")
    implementation("org.jetbrains.skiko:skiko-awt:${graphiteSkikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:${graphiteSkikoVersion.get()}")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko") {
            useVersion(graphiteSkikoVersion.get())
            because("Compose offscreen integration probe requires a coherent Skiko 0.153 runtime")
        }
    }
}

application {
    mainClass.set("probe.GraphiteComposeOffscreenIntegrationProbeKt")
}

tasks.named<JavaExec>("run") {
    environment(
        "GRAPHITE_COMPOSE_PROBE_OUTPUT_DIR",
        layout.buildDirectory.dir("probe").get().asFile.absolutePath
    )
    environment("GRAPHITE_SKIKO_VERSION", graphiteSkikoVersion.get())

    providers.environmentVariable("GRAPHITE_COMPOSE_SOURCE_PNG").orNull?.let {
        environment("GRAPHITE_COMPOSE_SOURCE_PNG", it)
    }
}
