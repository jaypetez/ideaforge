pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories: one list, reviewed in one place.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ideaforge"

// :core      pure Kotlin/JVM port of src/core + src/runtime (no Android, no clock, no network)
// :providers pure Kotlin/JVM port of src/providers (HTTP policy and adapters)
// :app       the Compose application
include(":core", ":providers", ":app")
