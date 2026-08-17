rootProject.name = "musicmeta-docs-samples"

// Composite build: resolves musicmeta-core/musicmeta-okhttp from local source, the same pattern as
// demo-cli and demo-web. To move to its own repo: remove includeBuild, add a JitPack repo.
includeBuild("..")

// The parent's version catalog, so the Kotlin plugin version has one home there instead of a
// second copy here. Goes when includeBuild does.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
