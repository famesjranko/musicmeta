plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.landofoz"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "musicmeta-okhttp"
        }
    }
}

dependencies {
    // Core module (exposes HttpClient, HttpResult types to consumers)
    api(project(":musicmeta-core"))

    // OkHttp (exposed as api so consumers can configure OkHttpClient)
    api(libs.okhttp)

    // JSON parsing (org.json used by HttpClient return types)
    implementation(libs.json)

    // Coroutines (for withContext(Dispatchers.IO))
    implementation(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.okhttp.mockwebserver)
}
