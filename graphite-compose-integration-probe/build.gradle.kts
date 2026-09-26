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

    // Force a coherent Skiko 0.153 stack. A mixed 0.152 core / 0.153 AWT stack
    // is not sufficient evidence for Graphite integration readiness.
    implementation("org.jetbrains.skiko:skiko:${graphiteSkikoVersion.get()}")
    implementation("org.jetbrains.skiko:skiko-awt:${graphiteSkikoVersion.get()}")

    implementation("org.jetbrains.skiko:skiko-graphite-awt:${graphiteSkikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-graphite-awt-runtime-windows-x64:${graphiteSkikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:${graphiteSkikoVersion.get()}")
}

application {
    mainClass.set("probe.GraphiteComposeIntegrationProbeKt")
}

tasks.named<JavaExec>("run") {
    environment("GRAPHITE_COMPOSE_PROBE_OUTPUT_DIR", layout.buildDirectory.dir("probe").get().asFile.absolutePath)
    environment("GRAPHITE_SKIKO_VERSION", graphiteSkikoVersion.get())
}
