pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            val relayKotlin = Regex("relay-kotlin = \"([^\"]+)\"").find(file("../gradle/libs.versions.toml").readText())!!.groupValues[1]
            version("kotlin", relayKotlin)
        }
    }
}
rootProject.name = "usuals-relay"
include(":core:model", ":core:parse", ":sync")
project(":core:model").projectDir = file("../core/model")
project(":core:parse").projectDir = file("../core/parse")
project(":sync").projectDir = file("../sync")
