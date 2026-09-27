plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "org.jetbrains.lets-plot.probe"
version = "0.153.0-PROBE"

val skikoVersion = providers.gradleProperty("skikoVersion").orElse("0.153.0")
val lwjglVersion = providers.gradleProperty("lwjglVersion").orElse("3.4.3")

kotlin {
    jvmToolchain(21)
    sourceSets {
        named("main") {
            kotlin.srcDir("../graphite-runtime-shared/src/main/kotlin")
        }
    }
}

base {
    archivesName.set("lets-plot-compose-graphite-runtime-windows-x64-probe")
}

dependencies {
    compileOnly("org.jetbrains.lets-plot:lets-plot-common:4.11.1-SNAPSHOT")
    compileOnly("org.jetbrains.lets-plot:lets-plot-compose-desktop:3.2.3-SNAPSHOT")

    compileOnly("org.jetbrains.skiko:skiko-awt:${skikoVersion.get()}")
    compileOnly("org.jetbrains.skiko:skiko-graphite-awt:${skikoVersion.get()}")
    compileOnly("org.lwjgl:lwjgl:${lwjglVersion.get()}")
    compileOnly("org.lwjgl:lwjgl-vulkan:${lwjglVersion.get()}")

    runtimeOnly("org.jetbrains.skiko:skiko-awt:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-graphite-awt:${skikoVersion.get()}")
    runtimeOnly("org.jetbrains.skiko:skiko-graphite-awt-runtime-windows-x64:${skikoVersion.get()}")

    runtimeOnly("org.lwjgl:lwjgl:${lwjglVersion.get()}")
    runtimeOnly("org.lwjgl:lwjgl-vulkan:${lwjglVersion.get()}")
    runtimeOnly("org.lwjgl:lwjgl:${lwjglVersion.get()}:natives-windows")
}

publishing {
    publications {
        create<MavenPublication>("runtimeBundle") {
            from(components["java"])
            artifactId = "lets-plot-compose-graphite-runtime-windows-x64-probe"
            pom {
                name.set("Lets-Plot Compose Graphite Runtime Windows x64 Probe")
                description.set(
                    "Probe-only optional runtime bundle used to validate Graphite/Vulkan packaging and activation boundaries."
                )
            }
        }
    }

    repositories {
        maven {
            name = "probe"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
