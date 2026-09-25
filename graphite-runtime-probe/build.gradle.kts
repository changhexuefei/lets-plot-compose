plugins {
    kotlin("jvm") version "2.4.20"
    application
}

group = "org.jetbrains.lets-plot.probe"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

val skikoVersion = providers.gradleProperty("skikoVersion").orElse("0.153.0")
val lwjglVersion = providers.gradleProperty("lwjglVersion").orElse("3.4.3")

dependencies {
    implementation("org.jetbrains.skiko:skiko-awt:${skikoVersion.get()}")
    implementation("org.jetbrains.skiko:skiko-graphite-awt:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-graphite-awt-runtime-windows-x64:${skikoVersion.get()}")

    implementation("org.lwjgl:lwjgl:${lwjglVersion.get()}")
    implementation("org.lwjgl:lwjgl-vulkan:${lwjglVersion.get()}")
    runtimeOnly("org.lwjgl:lwjgl:${lwjglVersion.get()}:natives-windows")
}

application {
    mainClass.set("probe.GraphiteRuntimeCapabilityProbeKt")
}

tasks.register<JavaExec>("graphiteRuntimeCapabilityProbe") {
    group = "verification"
    description = "Creates a real Vulkan-backed Skiko Graphite context and recorder on Windows."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("probe.GraphiteRuntimeCapabilityProbeKt")
    environment("GRAPHITE_SKIKO_VERSION", skikoVersion.get())
    environment("GRAPHITE_LWJGL_VERSION", lwjglVersion.get())
    environment("GRAPHITE_PROBE_OUTPUT_DIR", layout.buildDirectory.dir("probe").get().asFile.absolutePath)
}
