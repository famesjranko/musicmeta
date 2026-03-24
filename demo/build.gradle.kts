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

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation("com.landofoz:musicmeta-core")
    implementation("com.landofoz:musicmeta-okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
