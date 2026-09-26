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
    }
}

val skikoVersion = providers.gradleProperty("skikoVersion").orElse("0.153.0")
val lwjglVersion = providers.gradleProperty("lwjglVersion").orElse("3.4.3")

dependencies {
    implementation("org.jetbrains.lets-plot:lets-plot-common:4.11.1-SNAPSHOT")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.1-SNAPSHOT")
    implementation("org.jetbrains.lets-plot:lets-plot-compose-desktop:3.2.3-SNAPSHOT")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    implementation("org.jetbrains.skiko:skiko-awt:${skikoVersion.get()}")
    implementation("org.jetbrains.skiko:skiko-graphite-awt:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-graphite-awt-runtime-windows-x64:${skikoVersion.get()}")

    implementation("org.lwjgl:lwjgl:${lwjglVersion.get()}")
    implementation("org.lwjgl:lwjgl-vulkan:${lwjglVersion.get()}")
    runtimeOnly("org.lwjgl:lwjgl:${lwjglVersion.get()}:natives-windows")
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.skiko") {
            useVersion(skikoVersion.get())
            because("Graphite offscreen composite probe requires a coherent Skiko 0.153 runtime")
        }
    }
}

application {
    mainClass.set("probe.GraphiteOffscreenCompositeProbeKt")
}

tasks.register<JavaExec>("graphiteOffscreenCompositeProbe") {
    group = "verification"
    description = "Runs the Graphite offscreen rendering, CPU readback, and raster Canvas compositing."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("probe.GraphiteOffscreenCompositeProbeKt")
    environment("GRAPHITE_SKIKO_VERSION", skikoVersion.get())
    environment("GRAPHITE_LWJGL_VERSION", lwjglVersion.get())
    environment("GRAPHITE_PROBE_OUTPUT_DIR", layout.buildDirectory.dir("probe").get().asFile.absolutePath)
}
