plugins {
    kotlin("jvm") version "2.1.0"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

// Formatting is shared with the parent build; conventions are not. A demo stands in for an external
// consumer, so the house rules about *how we build internals* would defeat the point of the canary —
// but nothing about line length or import order affects whether this compiles against the published
// surface, and this is the worked example people read. Same `.editorconfig`, same ktlint.
//
// Read from the parent catalog rather than hardcoded: two copies of a version number is the exact
// defect that let the gate and the write-time hook drift apart. Moving demo/ to its own repo already
// means dropping `includeBuild("..")` in settings.gradle.kts; this line goes at the same time.
ktlint {
    version.set(
        file("../gradle/libs.versions.toml").readLines()
            .first { it.startsWith("ktlint-cli = ") }
            .substringAfter('"').substringBefore('"'),
    )
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
}
