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

rootProject.name = "musicmeta-docs-samples-android"

// Composite build: resolves musicmeta-core/musicmeta-android from local source, the same pattern
// as docs-samples. To move to its own repo: remove includeBuild, add a JitPack repo.
includeBuild("..")

// The parent's version catalog, so a plugin/library version has one home there instead of a
// second copy here. Goes when includeBuild does.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
