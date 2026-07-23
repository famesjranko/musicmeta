plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// Applied to every module rather than per-module, so a new module is formatted by default instead
// of by remembering. `demo/` is a separate composite build and is not covered — it is the consumer
// canary, and its job is to compile against the published surface, not to match house style.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt.yml"))
        buildUponDefaultConfig = true
        // Per module, not one shared file: `detektBaseline` writes the whole baseline for the
        // module it runs in, so three modules pointed at one path would each overwrite the others.
        baseline = rootProject.file("config/detekt-baseline-${project.name}.xml")
    }

    // Type resolution, and the reason the plain task is switched off rather than left alongside.
    //
    // `detekt` analyses syntax only. Every rule that has to resolve a type — UnsafeCallOnNullableType,
    // UnnecessaryNotNullOperator, UnreachableCode and the rest — silently does nothing under it and
    // reports no finding and no warning, so the run is green for reasons that have nothing to do
    // with the code. detektMain/detektTest resolve the compiled classpath and found 57 issues the
    // gate had never reported, including a dead elvis branch on a non-null field.
    //
    // Leaving `detekt` enabled would not be harmless: it is wired into Gradle's `check`, so
    // `./gradlew build` would run the untyped pass again after ./check has already run the typed
    // one — the same rules, a weaker answer, twice the time.
    //
    // These tasks are marked EXPERIMENTAL by the plugin. That is not a reason to avoid them: in
    // detekt 1.23.x *every* route to type resolution is experimental — these tasks, hand-wiring
    // `classpath`/`jvmTarget`, the CLI flags, and the compiler plugin. The choice is typed and
    // experimental, or stable and not actually checking. (#58)
    //
    // `matching {}.configureEach` rather than `named()`: the Android plugin registers `check` after
    // this block runs, so `named("check")` fails configuration on musicmeta-android.
    tasks.matching { it.name == "detekt" }.configureEach { enabled = false }
    tasks.matching { it.name == "check" }.configureEach { dependsOn("detektMain", "detektTest") }
}

// The oracle for scripts/checks/test_code_mask.py: Kotlin's own lexer, resolved through Gradle so
// the classpath is never a hardcoded ~/.gradle path (content-addressed, and CI moves
// GRADLE_USER_HOME). It lives on the root project, which is not published, so nothing here can
// reach api/*.api or a consumer's classpath.
//
// kotlin-stdlib is not optional: javac links against the embeddable jar alone, but IElementType's
// static init needs kotlin/jvm/internal/markers/KMappedMarker, so omitting it fails only at runtime.
val ktLexer: Configuration by configurations.creating
dependencies {
    ktLexer(libs.kotlin.compiler.embeddable)
    ktLexer(libs.kotlin.stdlib)
}

tasks.register("ktLexerClasspath") {
    description = "Print the classpath holding Kotlin's lexer, for the code-mask differential test."
    val classpath = ktLexer
    // Resolution at execution time, not configuration time — otherwise every Gradle invocation in
    // the repo pays for it.
    doLast { println(classpath.asPath) }
}

// Public ABI baselines live in each module's api/ directory. apiCheck is wired into `check`, so
// `./gradlew build` fails when a signature diverges from the committed dump. Regenerate with
// `./gradlew apiDump` and review the resulting diff — that diff is the record of an intentional
// API change.
apiValidation {
    // KSP output lands in the same classes dir BCV reads, so Room and Hilt generated types would
    // otherwise appear in the android baseline and churn on every Room/Hilt version bump. They are
    // generated, not authored — the authored surface they stand in for (EnrichmentCacheDao,
    // EnrichmentCacheDatabase, HiltEnrichmentModule) is still tracked.
    //
    // ignoredClasses is an exact-match set with no wildcard support: a new @Dao interface,
    // @Database, or Hilt @Provides method adds a generated class that must be listed here by hand.
    // A new method on an existing DAO does not — it lands on the already-ignored _Impl class.
    // Nested types need their own entry, matched by binary name (Outer$Nested).
    ignoredPackages += "hilt_aggregated_deps"
    ignoredClasses += listOf(
        "com.landofoz.musicmeta.android.cache.EnrichmentCacheDao_Impl",
        // Nested types are not covered by their outer class, and are matched by their binary name.
        "com.landofoz.musicmeta.android.cache.EnrichmentCacheDao_Impl\$Companion",
        "com.landofoz.musicmeta.android.cache.EnrichmentCacheDatabase_Impl",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideEnrichmentCacheDaoFactory",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideEnrichmentCacheDatabaseFactory",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideRoomEnrichmentCacheFactory",
    )
}
