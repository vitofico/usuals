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
