@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.library")
}

group = "org.jetbrains.lets-plot.smoke"
version = "1.0-SNAPSHOT"

kotlin {
    androidTarget()

    wasmJs {
        outputModuleName = "letsPlotConsumerSmoke"
        browser {
            commonWebpackConfig {
                outputFileName = "letsPlotConsumerSmoke.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)

                implementation("org.jetbrains.lets-plot:lets-plot-kotlin:4.15.1-SNAPSHOT")
                implementation("org.jetbrains.lets-plot:lets-plot-compose:3.2.3-SNAPSHOT")
            }
        }
    }
}

android {
    namespace = "org.jetbrains.letsplot.smoke.mpp"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
