pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor isn't published to Maven Central - jitpack builds
        // it straight from the GitHub release tag.
        maven("https://jitpack.io")
    }
}

rootProject.name = "mofy"
include(":app")
