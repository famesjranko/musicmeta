plugins {
    kotlin("jvm") version "2.1.0"
    application
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
