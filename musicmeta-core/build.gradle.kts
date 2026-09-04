import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.publish)
    // Carries the contract base classes a test in another module subclasses. A class in this
    // module's `src/test` is invisible to `musicmeta-android` and `musicmeta-okhttp`, which depend
    // on the main artifact only; `src/testFixtures` is the source set they can consume.
    // The variants it adds are kept out of the published artifact at the bottom of this file.
    `java-test-fixtures`
}

group = "io.github.famesjranko"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Without this, Kotlin targets whatever JDK runs Gradle while Java targets 17, and the build fails
// on any machine whose default is not 17. The values coincide only by accident on a JDK 17 box.
//
// `jvmToolchain(17)` is the more correct fix and was rejected: on a JDK-21-only machine it needs
// the foojay resolver, which downloads a JDK from a third-party service at build time — a
// supply-chain surface this repo does not otherwise have. Accepted gap: Kotlin resolves JDK classes
// from the running JDK, so a JDK-18+ API can compile locally and fail at runtime on 17. CI on 17
// catches it.
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }

    // Every published declaration states its visibility and its return type, at the compiler
    // rather than at review of the `api/*.api` diff. Main sources only — tests are unaffected.
    explicitApi()
}

// secrets.properties is where the keys actually live on a developer machine — it is what
// secrets.properties.example tells you to create, and what demo-web already reads. Without this the
// e2e suite runs keyless and still reports success, because every key-requiring test skips itself.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

tasks.withType<Test> {
    // Forward system properties to test JVM (for E2E test gating)
    systemProperty("include.e2e", System.getProperty("include.e2e") ?: "false")
    systemProperty("include.probe", System.getProperty("include.probe") ?: "false")

    // Forward API keys. Precedence is the one secrets.properties itself documents — the file wins
    // over the environment — with an explicit -D above both, so one run can override without edits.
    val apiKeys = mapOf(
        "lastfm.apikey" to "LASTFM_API_KEY",
        "fanarttv.apikey" to "FANARTTV_API_KEY",
        "discogs.token" to "DISCOGS_TOKEN",
        "listenbrainz.token" to "LISTENBRAINZ_TOKEN",
    )
    apiKeys.forEach { (prop, env) ->
        systemProperty(
            prop,
            System.getProperty(prop) ?: secrets.getProperty(prop) ?: System.getenv(env) ?: "",
        )
    }
}

// The daily provider drift watch, as its own task rather than a filter over `test`.
//
// Two reasons, both of which silently disarmed the watch when it was a filter. `test` is cacheable
// and its inputs do not change between scheduled runs, so every run after the first was UP-TO-DATE:
// the job passed daily having asked no upstream anything. And the e2e suite selects
// `com.landofoz.musicmeta.e2e.*`, so a manual suite run overwrote the pin's own report.
//
// Never part of `check` or `build`: it makes live third-party calls, and a merge must not wait on
// somebody else's uptime.
val schemaPin by tasks.registering(Test::class) {
    description = "Ask each pinned upstream route whether the fields its mapper reads are still there."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("com.landofoz.musicmeta.drift.SchemaPinE2ETest") }
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/schemaPin"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/schemaPin"))
    // The answer is a third party's and changes without a commit here, so nothing about this repo
    // says whether a previous result still holds. Both are needed: `upToDateWhen` alone still lets
    // the build cache serve a stored result for unchanged inputs.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// Core is dependency-minimal JVM (ARCHITECTURE.md), which is what lets a server or desktop
// consumer take the engine without an Android artifact or a wire library. A fourth entry below is
// imposed on every consumer's classpath at the next release and cannot be removed without a break.
// `apiCheck` sees nothing, because a transitive is not part of the ABI; the published POM is
// baselined instead, so adding one fails `check` until `make pom-dump` records it. The baseline
// reads coordinates, not reasons, so each entry still carries the argument for its being here.
dependencies {
    // The concurrency primitive the whole engine is built on.
    implementation(libs.kotlinx.coroutines.core)

    // JSON parsing. `implementation`, so it stays off the consumer's compile classpath — they
    // resolve nothing of ours through org.json types.
    implementation(libs.json)

    // The cache payload format. `api` rather than `implementation`: a consumer serializing our
    // cache entries compiles against it, so its version is part of the published contract.
    api(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.bundles.testing)

    // The contract bases hold `@Test` methods and `runTest` bodies, so they need the test
    // dependencies to compile in this source set as well as in `src/test`.
    testFixturesImplementation(libs.bundles.testing)
    // HttpClientContract asserts against HttpClient's org.json return types directly.
    testFixturesImplementation(libs.json)
}

mavenPublishing {
    // A stage rehearsal passes -PautoRelease=false, leaving the deployment validated-but-
    // unpublished in the portal so it can be inspected and dropped. See docs/project/release.md.
    publishToMavenCentral(
        SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = project.findProperty("autoRelease") != "false",
    )
    if (project.hasProperty("signing.keyId") || project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates("io.github.famesjranko", "musicmeta-core", version.toString())
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

// `java-test-fixtures` attaches its variants to the `java` component, which this plugin publishes
// from in automatic mode. Left alone that ships a `musicmeta-core-test-fixtures` capability and its
// jars — a new released surface under CLAUDE.md's compatibility rule, from a source set that exists
// to serve this repo's own tests. Skipping the three variants keeps the published artifact set
// exactly what it was.
//
// Deferred to `afterEvaluate` because `testFixturesSourcesElements` is registered lazily by the
// `mavenPublishing {}` block's own `withSourcesJar()`; naming it any earlier — including here, in
// script order — fails with "Configuration with name 'testFixturesSourcesElements' not found".
//
// The suppression depends on this plugin's automatic-mode behaviour. After any bump of
// `vanniktech.publish` or the Gradle wrapper, re-run `publishToMavenLocal` and grep the generated
// `.module` for `testfixtures`: a silent regression here publishes a new artifact.
project.afterEvaluate {
    components.named("java", AdhocComponentWithVariants::class) {
        withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
        withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
        withVariantsFromConfiguration(configurations["testFixturesSourcesElements"]) { skip() }
    }
}
