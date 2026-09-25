pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }

    val smokeComposeVersion = System.getenv("SMOKE_COMPOSE_VERSION")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "1.12.1"

    plugins {
        kotlin("jvm") version "2.4.20"
        kotlin("plugin.compose") version "2.4.20"
        id("org.jetbrains.compose") version smokeComposeVersion
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        fun localSmokeRepo(envName: String) {
            val repoPath = System.getenv(envName)
                ?.takeIf { it.isNotBlank() }
                ?: error("Missing required environment variable: $envName")
            maven {
                name = envName
                url = uri(file(repoPath))
            }
        }

        localSmokeRepo("LETS_PLOT_CORE_REPO")
        localSmokeRepo("LETS_PLOT_KOTLIN_REPO")
        localSmokeRepo("LETS_PLOT_COMPOSE_REPO")

        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "lets-plot-compose-consumer-smoke"
