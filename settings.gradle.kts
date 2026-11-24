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
        // Maven Central rate-limits large Robolectric test artifacts on CI runners.
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content { includeGroup("org.robolectric") }
        }
        mavenCentral()
    }
}

rootProject.name = "usuals"

include(
    ":app",
    ":core:model",
    ":core:parse",
    ":data:local",
    ":llm",
    ":smart",
    ":sync",
    ":recipe",
    ":auth",
    ":capture",
    ":enrich",
    ":testing",
)
