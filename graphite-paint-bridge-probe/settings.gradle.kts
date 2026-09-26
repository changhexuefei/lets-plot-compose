pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/cmp/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        System.getenv("LETS_PLOT_CORE_REPO")?.takeIf { it.isNotBlank() }?.let { repo ->
            maven { url = uri(repo) }
        }
        System.getenv("LETS_PLOT_KOTLIN_REPO")?.takeIf { it.isNotBlank() }?.let { repo ->
            maven { url = uri(repo) }
        }
        System.getenv("LETS_PLOT_COMPOSE_REPO")?.takeIf { it.isNotBlank() }?.let { repo ->
            maven { url = uri(repo) }
        }
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/cmp/dev")
    }
}

rootProject.name = "graphite-paint-bridge-probe"
