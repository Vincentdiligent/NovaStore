pluginManagement {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NovaStore"

include(":app")

include(":core:model")
include(":core:common")
include(":core:ui")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:security")
include(":core:downloader")
include(":core:installer")
include(":core:updater")
include(":core:playapi")

include(":domain")

include(":data")

include(":feature:home")
include(":feature:search")
include(":feature:details")
include(":feature:installed")
include(":feature:updates")
include(":feature:downloads")
include(":feature:settings")
include(":feature:account")
