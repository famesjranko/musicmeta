plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Not a consumer canary like demo-cli/demo-web — this module is never published and has no
// application entry point. Its only job is to compile scripts/checks/check_doc_samples.py's
// android.md output against the real Android/Room/Hilt/WorkManager toolchain, so a sample that
// drifts from the real API fails a build instead of lying to the next reader. A plain JVM module
// (docs-samples/) cannot see these types; this is the Android-toolchain half of the same check.
// No ktlint here: the generated sources are extracted verbatim from markdown, not written by hand,
// so house formatting has nothing to say about them.

android {
    namespace = "doc.samples.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // android.md's Hilt-module snippet reads `BuildConfig.LASTFM_KEY` as "your own app's
        // generated build-config field" — a real field here, not a prelude placeholder, since it
        // is genuinely the reader's own app-level concern rather than anything musicmeta defines.
        buildConfigField("String", "LASTFM_KEY", "\"lastfm-key\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val generatedSamplesDir = layout.buildDirectory.dir("generated-samples/src/main/kotlin")

// Runs the same extractor docs-samples/ runs, ahead of compileDebugKotlin, so that task alone is
// the full Android doc-samples gate. Declared as an input so a docs/guides/ edit is picked up
// without a `--rerun-tasks`; declared as an output directory so a stale generated file from a
// since-removed fence does not survive into the next compile.
val extractDocSamples =
    tasks.register<Exec>("extractDocSamples") {
        inputs.dir(rootProject.projectDir.resolve("../docs/guides"))
        inputs.file(rootProject.projectDir.resolve("../scripts/checks/check_doc_samples.py"))
        outputs.dir(generatedSamplesDir)
        commandLine(
            "python3",
            rootProject.projectDir.resolve("../scripts/checks/check_doc_samples.py").absolutePath,
            "--root",
            rootProject.projectDir.resolve("..").absolutePath,
            "--android-out",
            generatedSamplesDir.get().asFile.absolutePath,
        )
    }

android.sourceSets.getByName("main") {
    kotlin.srcDir(generatedSamplesDir)
}

// AGP registers its Kotlin compile tasks lazily, in a variant callback that runs after this script
// body — `tasks.named("compileDebugKotlin")` here would fail with "task not found". `configureEach`
// on the task type applies to a task whenever AGP creates it, debug and release alike.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(extractDocSamples)
}

dependencies {
    implementation("io.github.famesjranko:musicmeta-core")
    implementation("io.github.famesjranko:musicmeta-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation(libs.bundles.room)
    implementation(libs.hilt.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    // AndroidPrelude.kt's `viewModelScope` stand-in for the ViewModel snippet — musicmeta-android
    // itself has no lifecycle dependency, this module needs its own.
    implementation(libs.lifecycle.viewmodel.ktx)
}
