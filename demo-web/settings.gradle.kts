rootProject.name = "musicmeta-demo-web"

// Composite build: resolves musicmeta-core from local source during development.
// To move to own repo: remove includeBuild, add JitPack repo + dependency.
//
// -PdemoCoreVersion=<version> skips this: a composite build substitutes the included project for
// any request matching its group:artifact regardless of the version asked for, so leaving
// includeBuild in place would silently keep resolving local source even with a version pinned in
// build.gradle.kts. The property's only caller is the Dockerfile.
if (!settings.startParameter.projectProperties.containsKey("demoCoreVersion")) {
    includeBuild("..")
}

// The parent's version catalog, so the plugin versions in build.gradle.kts have one home there
// instead of a second copy here. Goes when includeBuild does.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
