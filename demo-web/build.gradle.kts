plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.ktlint)
}

// Formatting is shared with the parent build; conventions are not. A demo stands in for an external
// consumer, so the house rules about *how we build internals* would defeat the point of the canary —
// but nothing about line length or import order affects whether this compiles against the published
// surface, and this is the worked example people read. Same `.editorconfig`, same ktlint.
//
// Read from the parent catalog rather than hardcoded: two copies of a version number is the exact
// defect that let the gate and the write-time hook drift apart. Moving demo-web/ to its own repo
// already means dropping `includeBuild("..")` in settings.gradle.kts; this line goes at the same time.
ktlint {
    version.set(libs.versions.ktlint.cli)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.landofoz.musicmeta.demoweb.MainKt")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// See musicmeta-core: without this the canary cannot be run on a machine whose default JDK is not 17.
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

// Unset, this stays a plain module request that the composite build in settings.gradle.kts
// substitutes for local source. Set (the Dockerfile's -PdemoCoreVersion), settings.gradle.kts
// drops that substitution and this pins the published Maven Central coordinate instead, so the
// image demos what a consumer actually gets from the artifact.
val demoCoreVersion = providers.gradleProperty("demoCoreVersion")

dependencies {
    implementation("io.github.famesjranko:musicmeta-core" + demoCoreVersion.map { ":$it" }.getOrElse(""))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
