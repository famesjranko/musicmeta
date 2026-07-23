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
