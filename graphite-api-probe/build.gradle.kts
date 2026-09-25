plugins {
    kotlin("jvm") version "2.4.20"
}

group = "org.jetbrains.lets-plot.probe"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

val skikoVersion = providers.gradleProperty("skikoVersion").orElse("0.153.0")

dependencies {
    compileOnly("org.jetbrains.skiko:skiko-awt:${skikoVersion.get()}")
    compileOnly("org.jetbrains.skiko:skiko-graphite-awt:${skikoVersion.get()}")
}

tasks.register("graphiteApiAvailabilityProbe") {
    group = "verification"
    description = "Compile-checks the isolated Skiko Graphite JVM API surface without activating a GPU backend."
    dependsOn(tasks.named("compileKotlin"))

    doLast {
        val outputDir = layout.buildDirectory.dir("probe").get().asFile
        outputDir.mkdirs()
        outputDir.resolve("graphite-api-availability.txt").writeText(
            """
            result=API_AVAILABLE
            probe.scope=compile-only
            platform=windows
            jdk=21
            skiko.version=${skikoVersion.get()}
            graphite.artifact=org.jetbrains.skiko:skiko-graphite-awt:${skikoVersion.get()}
            runtime.activation=NOT_ATTEMPTED
            compose.integration=NOT_ATTEMPTED
            adoption.status=NOT_STARTED
            """.trimIndent() + "\n"
        )
    }
}
