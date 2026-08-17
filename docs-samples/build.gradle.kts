plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Not a consumer canary like demo-cli/demo-web — this module is never published and has no
// application entry point. Its only job is to compile scripts/checks/check_doc_samples.py's output,
// so a docs/guides/*.md Kotlin fence that drifts from the real API fails a build instead of a reader.
// No ktlint here: the generated sources are extracted verbatim from markdown, not written by hand,
// so house formatting has nothing to say about them.

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// See musicmeta-core: without this the compile fails on a machine whose default JDK is not 17.
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

val generatedSamplesDir = layout.buildDirectory.dir("generated-samples/src/main/kotlin")

// Runs the extractor ahead of compileKotlin so `compileKotlin` alone is the full doc-samples gate;
// `check_doc_samples.py`'s own docstring covers what it does with each fence. Declared as an input
// so a docs/guides/ edit is picked up without a `--rerun-tasks`; declared as an output directory so
// a stale generated file from a since-removed fence does not survive into the next compile.
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
            "--out",
            generatedSamplesDir.get().asFile.absolutePath,
        )
    }

sourceSets {
    main {
        kotlin.srcDir(generatedSamplesDir)
    }
}

tasks.named("compileKotlin") {
    dependsOn(extractDocSamples)
}

dependencies {
    implementation("io.github.famesjranko:musicmeta-core")
    implementation("io.github.famesjranko:musicmeta-okhttp")
    // musicmeta-core depends on this as `implementation`, not `api` — a consumer whose sample calls
    // `Flow`-returning API (`enrichBatch`) needs its own copy, the same reason demo-cli has one.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Prelude.kt's stand-in for "your existing OkHttpClient" — the actual dependency an
    // OkHttpEnrichmentClient sample needs, not musicmeta-okhttp's wrapper around one.
    implementation(libs.okhttp)
}
