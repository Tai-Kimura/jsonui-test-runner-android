pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jsonui-test-runner-android"
include(":jsonuitestrunner")
include(":jsonuitestrunner-keep")
// Not published: Compose screens for the on-device arms that need one.
include(":compose-probe")
