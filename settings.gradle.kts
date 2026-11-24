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
        // Maven Central rate-limits the large API 33 Robolectric test fixture on CI runners.
        maven("https://maven-central.storage-download.googleapis.com/maven2") {
            content {
                onlyForConfigurations("robolectricSdkApi33")
                includeVersion("org.robolectric", "android-all-instrumented", "13-robolectric-9030017-i7")
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
