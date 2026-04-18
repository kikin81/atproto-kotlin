rootProject.name = "kikinlex"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

include(":at-protocol-runtime")
include(":at-protocol-models")
include(":at-protocol-generator")
include(":at-protocol-oauth")
include(":samples:android")
