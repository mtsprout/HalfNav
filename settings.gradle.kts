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

rootProject.name = "HalfNav"
include(":core")
// Set HALFNAV_CORE_ONLY=1 to build/test the shared core without the Android SDK.
if (System.getenv("HALFNAV_CORE_ONLY") == null) include(":app")
