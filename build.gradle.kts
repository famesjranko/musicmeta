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

// Read here rather than inside `subprojects {}`: the type-safe `libs` accessor resolves against the
// root project only, and the block below runs against each subproject.
val ktlintVersion = libs.versions.ktlint.cli.get()

// Applied to every module rather than per-module, so a new module is formatted by default instead
// of by remembering. `demo-cli/` is a separate composite build and is not covered — it is the
// consumer canary, and its job is to compile against the published surface, not to match house
// style.
subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // Pinned, not the plugin's default. Left implicit, a ktlint-gradle bump silently swaps the
    // rule set underneath the gate — and the write-time hook has no number to match against, so it
    // formats to a style the gate never asked for. See `ktlint-cli` in libs.versions.toml.
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
    }

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
    // Both untyped tasks become aliases for their typed pair rather than being switched off. A
    // disabled task still runs its dependencies and still reports success, so `./gradlew detekt` —
    // the conventional command, and what an IDE or a stray script will reach for — keeps meaning
    // "analyse this project" instead of quietly meaning "do nothing, successfully". The same
    // applies to `detektBaseline`, which writes the *whole* baseline from an untyped run and would
    // otherwise drop every type-resolved entry the moment someone regenerated it.
    tasks.matching { it.name == "detekt" }.configureEach {
        enabled = false
        dependsOn("detektMain", "detektTest")
    }
    tasks.matching { it.name == "detektBaseline" }.configureEach {
        enabled = false
        dependsOn("detektBaselineMain", "detektBaselineTest")
    }
    // `check` already reaches the typed tasks through the alias above; naming them is belt and
    // braces against the alias being unpicked without the dependency being noticed.
    tasks.matching { it.name == "check" }.configureEach { dependsOn("detektMain", "detektTest") }

    // `./check` passes -Pmusicmeta.rerunTests, and it is the reason a green run means the tests
    // passed on this tree rather than on some tree.
    //
    // The build cache is on and its directory is shared by every checkout on the machine, so a Test
    // task's outputs — its JUnit reports included — are restored wholesale on an input-hash hit.
    // `./check` could therefore report green having run no test at all, against reports another
    // tree wrote days earlier. Gradle's keys cover declared inputs only; anything a test reads that
    // Gradle cannot see is free to differ between the tree that stored the entry and the one
    // reusing it. Both lines are needed: `upToDateWhen` alone still lets the cache serve a result.
    //
    // Behind a property rather than always on, because the edit loop and the IDE want the cache;
    // the gate is the one place that must not take an answer on trust.
    tasks.withType<Test>().configureEach {
        if (providers.gradleProperty("musicmeta.rerunTests").isPresent) {
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
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
        "com.landofoz.musicmeta.android.cache.NegativeCacheDao_Impl",
        "com.landofoz.musicmeta.android.cache.NegativeCacheDao_Impl\$Companion",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideEnrichmentCacheDaoFactory",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideEnrichmentCacheDatabaseFactory",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideNegativeCacheDaoFactory",
        "com.landofoz.musicmeta.android.di.HiltEnrichmentModule_ProvideRoomEnrichmentCacheFactory",
    )
}
