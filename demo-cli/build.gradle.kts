plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.ktlint)
}

// Formatting is shared with the parent build; conventions are not. A demo stands in for an external
// consumer, so the house rules about *how we build internals* would defeat the point of the canary —
// but nothing about line length or import order affects whether this compiles against the published
// surface, and this is the worked example people read. Same `.editorconfig`, same ktlint.
//
// Read from the parent catalog rather than hardcoded: two copies of a version number is the exact
// defect that let the gate and the write-time hook drift apart. Moving demo-cli/ to its own repo
// already means dropping `includeBuild("..")` in settings.gradle.kts; this line goes at the same
// time.
ktlint {
    version.set(libs.versions.ktlint.cli)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.landofoz.musicmeta.demo.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// See musicmeta-core: without this the canary cannot be run on a machine whose default JDK is not 17.
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation("io.github.famesjranko:musicmeta-core")
    implementation("io.github.famesjranko:musicmeta-okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(libs.junit)
}

// `./check` passes -Pmusicmeta.rerunTests: the build cache is on and shared by every checkout on
// the machine, so without this a Test task's reports — and its green — can be restored from a run
// in another tree on another day. See the root build for the whole argument.
tasks.withType<Test>().configureEach {
    if (providers.gradleProperty("musicmeta.rerunTests").isPresent) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
