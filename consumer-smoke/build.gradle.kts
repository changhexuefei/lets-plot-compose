import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    application
}

group = "org.jetbrains.lets-plot.smoke"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material)

    // Deliberately consume published artifacts only: no project(":...") dependencies.
    implementation("org.jetbrains.lets-plot:lets-plot-common:4.11.1-SNAPSHOT")
    implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.1-SNAPSHOT")
    implementation("org.jetbrains.lets-plot:lets-plot-compose:3.2.3-SNAPSHOT")

    implementation("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass.set("smoke.ConsumerSmokeKt")
}

val smokeRenderApi = providers.environmentVariable("SMOKE_RENDER_API").orElse("SOFTWARE")
val smokeSkikoVersion = providers.environmentVariable("SMOKE_SKIKO_VERSION").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

if (smokeSkikoVersion != null) {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.skiko") {
                useVersion(smokeSkikoVersion)
                because("Consumer smoke upgrade probe explicitly tests Skiko $smokeSkikoVersion")
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("java.awt.headless", "false")
    systemProperty("skiko.renderApi", smokeRenderApi.get())
}
