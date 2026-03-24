import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.vanniktech.publish)
}

group = "io.github.famesjranko"
version = "0.8.0"

android {
    namespace = "com.landofoz.musicmeta.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
            arg("room.generateKotlin", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Enrichment core
    implementation(project(":musicmeta-core"))

    // Room
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.bundles.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (project.hasProperty("signing.keyId")) {
        signAllPublications()
    }
    configure(AndroidSingleVariantLibrary(
        variant = "release",
        sourcesJar = true,
        // AGP 8.7.3 bundles Dokka 1.x which cannot parse Kotlin 2.1 metadata (binary version 2.1.0,
        // expected 1.4.2). Javadoc generation fails at compile time for all sealed classes from
        // musicmeta-core. Disabled until AGP is upgraded to a version bundling Dokka 2.x.
        publishJavadocJar = false,
    ))
    coordinates("io.github.famesjranko", "musicmeta-android", version.toString())
    pom {
        name.set("musicmeta-android")
        description.set("Android extensions for musicmeta-core — Room cache, Hilt DI, WorkManager background enrichment")
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
