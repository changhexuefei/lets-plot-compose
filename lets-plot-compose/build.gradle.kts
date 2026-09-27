/*
 * Copyright (c) 2026. JetBrains s.r.o.
 * Use of this source code is governed by the MIT license that can be found in the LICENSE file.
 */

@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.io.File

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.library")
    id("org.jetbrains.compose")
    `maven-publish`
    signing
}

val androidComposeBom = extra["androidx.compose.bom"] as String
val letsPlotVersion = extra["letsPlot.version"] as String
val letsPlotKotlinVersion = extra["letsPlotKotlin.version"] as String
val kotlinxCoroutinesVersion = extra["kotlinx.coroutines.version"] as String
val kotlinxDatetimeVersion = extra["kotlinx.datetime.version"] as String
val kotlinxBrowserVersion = extra["kotlinx.browser.version"] as String
val kotlinLoggingVersion = extra["kotlinLogging.version"] as String

val graphiteCompatibilityEnabled =
    providers.gradleProperty("letsPlot.graphite.compatibility.enabled").orElse("false")
val graphiteCompatibilitySkikoVersion =
    providers.gradleProperty("letsPlot.graphite.compatibility.skiko").orElse("UNVERIFIED")
val graphiteCompatibilityProfile =
    providers.gradleProperty("letsPlot.graphite.compatibility.profile")
        .orElse("PRODUCTION_NATIVE_BASELINE")
val composeVersionForCompatibility =
    providers.gradleProperty("compose.version").orElse("UNVERIFIED")

val graphiteCompatibilityGeneratedDir =
    layout.buildDirectory.dir("generated/graphiteCompatibility/desktopMain/kotlin")

val generateDesktopGraphiteCompatibilityMarker by tasks.registering {
    inputs.property("graphiteCompatibilityEnabled", graphiteCompatibilityEnabled)
    inputs.property("graphiteCompatibilitySkikoVersion", graphiteCompatibilitySkikoVersion)
    inputs.property("graphiteCompatibilityProfile", graphiteCompatibilityProfile)
    inputs.property("composeVersionForCompatibility", composeVersionForCompatibility)
    outputs.dir(graphiteCompatibilityGeneratedDir)

    doLast {
        val outputDir = graphiteCompatibilityGeneratedDir.get().asFile
        val outputFile = File(
            outputDir,
            "org/jetbrains/letsPlot/compose/DesktopGraphiteCompatibilityMarker.kt"
        )
        outputFile.parentFile.mkdirs()

        val enabled = graphiteCompatibilityEnabled.get().equals("true", ignoreCase = true)
        val composeVersion = composeVersionForCompatibility.get()
        val skikoVersion = graphiteCompatibilitySkikoVersion.get()
        val profile = graphiteCompatibilityProfile.get()

        fun quoted(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        outputFile.writeText(
            """
            |package org.jetbrains.letsPlot.compose
            |
            |/**
            | * Build-time marker consumed only by the optional Graphite runtime bootstrap.
            | *
            | * Normal production builds intentionally publish ELIGIBLE=false. CI may opt in
            | * only after verifying the exact Compose/Skiko compile classpath.
            | */
            |internal object DesktopGraphiteCompatibilityMarker {
            |    const val SCHEMA: Int = 1
            |    const val ELIGIBLE: Boolean = $enabled
            |    const val COMPOSE_VERSION: String = "${quoted(composeVersion)}"
            |    const val COMPILE_SKIKO_VERSION: String = "${quoted(skikoVersion)}"
            |    const val PROFILE: String = "${quoted(profile)}"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
                }
            }
        }
    }

    androidTarget {
        publishLibraryVariants("release")
    }

    wasmJs {
        //outputModuleName = "lets-plot-compose"
        browser()
    }

    sourceSets {
        named("commonMain") {
            dependencies {
                compileOnly(compose.runtime)
                compileOnly(compose.ui)
                compileOnly(compose.foundation)
                compileOnly(compose.components.resources)

                compileOnly("org.jetbrains.lets-plot:lets-plot-kotlin:$letsPlotKotlinVersion")
                compileOnly("org.jetbrains.lets-plot:lets-plot-common:$letsPlotVersion")

                api("io.github.oshai:kotlin-logging:$kotlinLoggingVersion")
            }
        }

        named("desktopMain") {
            kotlin.srcDir(graphiteCompatibilityGeneratedDir)

            dependencies {
                compileOnly(compose.desktop.currentOs)
                compileOnly(compose.components.resources)
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.components.resources)
                implementation("org.jetbrains.lets-plot:lets-plot-kotlin-kernel:$letsPlotKotlinVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinxDatetimeVersion")
                implementation("org.jetbrains.lets-plot:lets-plot-common:$letsPlotVersion")
                implementation("org.jetbrains.lets-plot:visual-testing:$letsPlotVersion")
                implementation(kotlin("test"))
            }
        }

        named("androidMain") {
            dependencies {
                implementation(project.dependencies.platform("androidx.compose:compose-bom:$androidComposeBom"))
                implementation("androidx.compose.ui:ui")
                implementation("androidx.compose.ui:ui-graphics")
                api(project(":platf-android"))
            }
        }

        wasmJsMain {
            dependencies {
                implementation("org.jetbrains.lets-plot:lets-plot-kotlin:$letsPlotKotlinVersion")
                implementation("org.jetbrains.lets-plot:lets-plot-common:$letsPlotVersion")

                implementation("org.jetbrains.kotlinx:kotlinx-browser:$kotlinxBrowserVersion")
            }
        }

    }
}

tasks.matching { task ->
    task.name == "compileKotlinDesktop" ||
        task.name == "compileTestKotlinDesktop" ||
        task.name == "desktopSourcesJar"
}.configureEach {
    dependsOn(generateDesktopGraphiteCompatibilityMarker)
}

android {
    namespace = "org.jetbrains.letsPlot.compose"

    compileSdk = (findProperty("android.compileSdk") as String).toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    defaultConfig {
        minSdk = (findProperty("android.minSdk") as String).toInt()
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false // true - error: when compiling demo cant resolve classes
//            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(11)
    }
}


///////////////////////////////////////////////
//  Publishing
///////////////////////////////////////////////

afterEvaluate {
    publishing {
        publications.forEach { pub ->
            with(pub as MavenPublication) {
                artifact(tasks.jarJavaDocs)

                pom {
                    name.set("Lets-Plot Compose Frontend")
                    description.set("Compose frontend for Lets-Plot multiplatform plotting library.")
                    url.set("https://github.com/JetBrains/lets-plot-compose")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://raw.githubusercontent.com/JetBrains/lets-plot-compose/master/LICENSE")
                        }
                    }
                    developers {
                        developer {
                            id.set("jetbrains")
                            name.set("JetBrains")
                            email.set("lets-plot@jetbrains.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/JetBrains/lets-plot-compose")
                    }
                }
            }
        }

        repositories {
            mavenLocal {
                url = uri("$rootDir/.maven-publish-dev-repo")
            }
            maven {
                // For SNAPSHOT publication use separate URL and credentials:
                if (version.toString().endsWith("-SNAPSHOT")) {
                    url = uri(rootProject.project.extra["mavenSnapshotPublishUrl"].toString())

                    credentials {
                        username = rootProject.project.extra["sonatypeUsername"].toString()
                        password = rootProject.project.extra["sonatypePassword"].toString()
                    }
                } else {
                    url = uri(rootProject.project.extra["mavenReleasePublishUrl"].toString())
                }
            }
        }
    }
}

signing {
    if (!(project.version as String).contains("SNAPSHOT")) {
        sign(publishing.publications)
    }
}
