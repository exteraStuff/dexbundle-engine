pluginManagement {
    includeBuild("../gradle-plugin")

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        exclusiveContent {
            forRepositories(
                maven("https://maven.aliucord.com/releases"),
                maven("https://maven.aliucord.com/snapshots"),
            )
            filter { includeGroup("com.aliucord") }
        }
    }
}

rootProject.name = "DexBundle Engine"

include(":engine")

include(":api")
