pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }

    plugins {
        kotlin("jvm") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        fun optionalLocalRepo(envName: String) {
            System.getenv(envName)
                ?.takeIf { it.isNotBlank() }
                ?.let { repoPath ->
                    maven {
                        name = envName
                        url = uri(file(repoPath))
                    }
                }
        }

        optionalLocalRepo("LETS_PLOT_CORE_REPO")
        optionalLocalRepo("LETS_PLOT_KOTLIN_REPO")
        optionalLocalRepo("LETS_PLOT_COMPOSE_REPO")

        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "lets-plot-compose-graphite-runtime-package-probe"
