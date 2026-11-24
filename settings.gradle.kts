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
        // Maven Central rate-limits large Robolectric SDK test fixtures on CI runners.
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content {
                onlyForConfigurations("robolectricSdkApi26", "robolectricSdkApi33", "robolectricSdkApi36")
                includeModule("org.robolectric", "android-all-instrumented")
            }
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
