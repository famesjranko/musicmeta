rootProject.name = "musicmeta-demo"

// Composite build: resolves musicmeta-core from local source during development.
// To move to own repo: remove includeBuild, add JitPack repo + dependency.
includeBuild("..")

// The parent's version catalog, so the plugin versions in build.gradle.kts have one home there
// instead of a second copy here. Goes when includeBuild does.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
