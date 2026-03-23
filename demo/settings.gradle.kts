rootProject.name = "musicmeta-demo"

// Composite build: resolves musicmeta-core from local source during development.
// To move to own repo: remove includeBuild, add JitPack repo + dependency.
includeBuild("..")
