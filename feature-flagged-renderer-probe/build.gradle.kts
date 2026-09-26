plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "org.jetbrains.lets-plot.probe"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../lets-plot-compose/src/desktopMain/kotlin")
        kotlin.include("probe/**")
        kotlin.include("org/jetbrains/letsPlot/compose/DesktopSkiaPaintBridge.kt")
        kotlin.include("org/jetbrains/letsPlot/compose/DesktopPlotRendererBridge.kt")
    }
}

val skikoVersion = providers.gradleProperty("skikoVersion").orElse("0.153.0")

dependencies {
    implementation("org.jetbrains.lets-plot:lets-plot-common:4.11.1-SNAPSHOT")
    implementation("org.jetbrains.lets-plot:lets-plot-compose-desktop:3.2.3-SNAPSHOT")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    implementation("org.jetbrains.skiko:skiko-awt:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:${skikoVersion.get()}")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko") {
            useVersion(skikoVersion.get())
            because("Feature-flagged renderer probe requires a coherent Skiko 0.153 runtime")
        }
    }
}

application {
    mainClass.set("probe.FeatureFlaggedRendererProbeKt")
}

tasks.register<JavaExec>("featureFlaggedRendererProbe") {
    group = "verification"
    description = "Validates the production Desktop renderer feature flag and offscreen raster seam."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("probe.FeatureFlaggedRendererProbeKt")
    environment("FEATURE_FLAGGED_RENDERER_PROBE_OUTPUT_DIR", layout.buildDirectory.dir("probe").get().asFile.absolutePath)
    environment("GRAPHITE_SKIKO_VERSION", skikoVersion.get())
}
