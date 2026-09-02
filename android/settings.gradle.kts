import org.gradle.api.initialization.resolve.RepositoriesMode

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
    }
}

rootProject.name = "motion-gesture-engine-android"
include(":motion-gesture-core")
include(":motion-gesture-recorder")
include(":motion-gesture-android-sensors")
include(":motion-gesture-replay")
