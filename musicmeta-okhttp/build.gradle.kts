import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

group = "io.github.famesjranko"
version = "0.8.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test> {
    systemProperty("include.e2e", System.getProperty("include.e2e") ?: "false")
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

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (project.hasProperty("signing.keyId") || project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates("io.github.famesjranko", "musicmeta-okhttp", version.toString())
    pom {
        name.set("musicmeta-okhttp")
        description.set("OkHttp adapter for musicmeta-core — plug in your OkHttpClient instance")
        url.set("https://github.com/famesjranko/musicmeta")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("famesjranko")
                name.set("Andy")
                url.set("https://github.com/famesjranko")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/famesjranko/musicmeta.git")
            developerConnection.set("scm:git:ssh://github.com/famesjranko/musicmeta.git")
            url.set("https://github.com/famesjranko/musicmeta")
        }
    }
}
