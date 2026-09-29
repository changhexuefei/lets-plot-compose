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

        val androidInstrumentedTest by getting {
            dependencies {
                implementation("org.jetbrains.compose.ui:ui-test-junit4:1.12.1")
                implementation("androidx.activity:activity-compose:1.10.1")
                implementation("androidx.test:core-ktx:1.6.1")
                implementation("androidx.test.ext:junit-ktx:1.2.1")
                implementation("androidx.test:runner:1.6.2")
            }
        }
    }
}

android {
    namespace = "org.jetbrains.letsplot.smoke.mpp"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets["androidTest"].manifest.srcFile("src/androidInstrumentedTest/AndroidManifest.xml")

    testOptions {
        animationsDisabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
