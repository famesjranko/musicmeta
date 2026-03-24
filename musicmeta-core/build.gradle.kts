import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.publish)
}

group = "com.landofoz"
version = "0.8.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<Test> {
    // Forward system properties to test JVM (for E2E test gating)
    systemProperty("include.e2e", System.getProperty("include.e2e") ?: "false")

    // Forward API keys (system property > environment variable > empty)
    val apiKeys = mapOf(
        "lastfm.apikey" to "LASTFM_API_KEY",
        "fanarttv.apikey" to "FANARTTV_API_KEY",
        "discogs.token" to "DISCOGS_TOKEN",
    )
    apiKeys.forEach { (prop, env) ->
        systemProperty(prop, System.getProperty(prop) ?: System.getenv(env) ?: "")
    }
}

dependencies {
    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // JSON parsing
    implementation(libs.json)

    // Serialization (for cache layer consumers)
    api(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.landofoz", "musicmeta-core", version.toString())
    pom {
        name.set("musicmeta-core")
        description.set("Music metadata enrichment engine — provider chains, identity resolution, caching")
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
