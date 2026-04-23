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
        // ADD THIS LINE BELOW
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Bifurcation_App_Android"
include(":app")