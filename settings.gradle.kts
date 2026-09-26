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

rootProject.name = "ilo-console"

include(":ilo-core")
// `-PcoreOnly` builds the protocol library without the Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
