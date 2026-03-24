# Stack Research

**Domain:** Kotlin/JVM music metadata enrichment library — v0.8.0 Production Readiness additions
**Researched:** 2026-03-24
**Confidence:** HIGH (all critical claims verified against Maven Central and official documentation)

---

## Context

This is a SUBSEQUENT MILESTONE research document. Do not re-research the existing validated stack
(Kotlin 2.1.0, coroutines 1.9.0, kotlinx.serialization 1.7.3, org.json, JUnit 4.13.2, Turbine 1.2.0).

This document covers only what is NEW for v0.8.0:
1. OkHttp adapter module (`musicmeta-okhttp`)
2. Stale-while-revalidate cache (no new deps — pure library changes)
3. Bulk enrichment with Flow (no new deps — Flow is in existing coroutines-core)
4. Maven Central publishing (new tooling required — OSSRH is gone)

---

## Recommended Stack — New Additions Only

### OkHttp Adapter Module

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| `com.squareup.okhttp3:okhttp` | **4.12.0** | HTTP client implementation for adapter module | See version decision below |
| `com.squareup.okhttp3:mockwebserver` | **4.12.0** | Integration testing mock server | Matches OkHttp 4.x coordinate; no JUnit-specific suffix needed in 4.x |

**Version decision — 4.12.0 vs 5.x:**

`docs/v0.8.0.md` specifies `okhttp = "4.12.0"`. This is correct. Do not upgrade to 5.x for this milestone.

OkHttp 5.x is the current stable series (5.3.2 as of Nov 2025), but it is the wrong choice here:

- OkHttp 5.x was compiled with Kotlin 2.2.x and brings `kotlin-stdlib:2.2.21` as a transitive
  dependency. `musicmeta-okhttp` compiles with Kotlin 2.1.0. Gradle resolves the higher transitive
  version silently for direct consumers, but a library that forces `stdlib 2.2.x` onto users of
  `musicmeta-okhttp` who may be on Kotlin 2.0.x is bad library hygiene. The Kotlin stdlib is a
  runtime dependency for the adapter's consumers, not just the adapter itself.
- OkHttp 5.x changes the MockWebServer artifact coordinate to `mockwebserver3-junit4` (new
  `mockwebserver3` package name). This migration delivers zero value for a thin adapter module.
- OkHttp 5.x drops Kotlin Multiplatform support. Not a concern here, but signals a more opinionated
  release with higher transitive impact.
- OkHttp 4.12.0 is the final 4.x release (Oct 2023). Its API is stable and feature-complete for
  all 12 `HttpClient` methods the adapter needs to implement.
- When the project bumps to Kotlin 2.2.x in a future milestone, upgrade the adapter to OkHttp 5.x
  at the same time. The two upgrades belong together.

**Confidence:** HIGH — verified via Maven Central version listing, OkHttp 4.x changelog (4.12.0 is
final 4.x release), OkHttp 5.x changelog (Kotlin 2.2.x requirement), and OkHttp GitHub README.

---

### Maven Central Publishing

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| `com.vanniktech.maven.publish` Gradle plugin | **0.36.0** | Publishes all 3 modules to Maven Central via Central Portal | OSSRH is shut down; this plugin directly supports the new Central Portal API |

**Critical: The approach in `docs/v0.8.0.md` Phase 4 no longer works.**

The plan describes configuring the built-in `maven-publish` plugin with OSSRH repository URLs
(`s01.oss.sonatype.org`, `oss.sonatype.org`). OSSRH was shut down on **June 30, 2025**. Those URLs
do not accept uploads. A compatibility shim exists but requires a Portal token anyway — there is
no reason to use it when the vanniktech plugin provides a cleaner path.

The `vanniktech/gradle-maven-publish-plugin` version 0.36.0:
- Supports `SonatypeHost.CENTRAL_PORTAL` — the new post-OSSRH publishing target
- Auto-configures sources jar and javadoc jar for Kotlin JVM targets (replaces the
  `java { withSourcesJar(); withJavadocJar() }` the plan mentions)
- Handles Android library variant publishing via `AndroidSingleVariantLibrary`
- Manages GPG signing via `signAllPublications()`
- Adds `publishToMavenCentral` and `publishAndReleaseToMavenCentral` tasks
- Latest stable: 0.36.0 (January 18, 2025); no newer stable version exists as of March 2026

**Confidence:** HIGH — verified via vanniktech plugin GitHub releases, Sonatype OSSRH sunset
announcement, and Sonatype OSSRH EOL page.

---

## No New Dependencies for Cache or Bulk Enrichment

**Stale-while-revalidate cache:** Pure library changes. New `CacheMode` enum, new method on
`EnrichmentCache` interface, modified `InMemoryEnrichmentCache`, `RoomEnrichmentCache`, and
`DefaultEnrichmentEngine`. No new Gradle dependencies.

**Bulk enrichment API:** `Flow<T>` and the `flow {}` builder are already in
`kotlinx-coroutines-core:1.9.0`, which is declared in `musicmeta-core/build.gradle.kts`. No new
Gradle dependencies.

---

## Version Catalog Changes (`gradle/libs.versions.toml`)

Add these entries only:

```toml
[versions]
okhttp = "4.12.0"
# vanniktech does not need a version entry — hardcode in plugin block, or add:
vanniktech-publish = "0.36.0"

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }

[plugins]
# Add alongside existing plugin entries:
vanniktech-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktech-publish" }
```

Note: The version catalog version entry for `vanniktech-publish` is optional but consistent with
the project's pattern of cataloguing all dependency versions centrally.

---

## New Module: `musicmeta-okhttp/build.gradle.kts`

Follow `musicmeta-core` pattern exactly — pure Kotlin JVM, no `afterEvaluate`, no Android.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

group = "com.landofoz"
version = "0.8.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":musicmeta-core"))
    api(libs.okhttp)
    implementation(libs.json)               // for org.json.JSONObject / JSONArray parsing

    testImplementation(libs.bundles.testing)
    testImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.landofoz", "musicmeta-okhttp", "0.8.0")
    pom {
        name = "musicmeta-okhttp"
        description = "OkHttp HttpClient adapter for musicmeta-core"
        url = "https://github.com/<owner>/music-enrichment"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        developers { developer { name = "<developer name>" } }
        scm {
            connection = "scm:git:git://github.com/<owner>/music-enrichment.git"
            url = "https://github.com/<owner>/music-enrichment"
        }
    }
}
```

Note: `api(libs.okhttp)` uses `api` scope (not `implementation`) so that consumers of
`musicmeta-okhttp` get OkHttp on their compile classpath — they need it to construct an
`OkHttpClient` to pass to `OkHttpEnrichmentClient`.

---

## Maven Central Publishing — Per-Module Config

### Root `build.gradle.kts`

Add the vanniktech plugin to the root with `apply false`:
```kotlin
alias(libs.plugins.vanniktech.publish) apply false
```

Remove the manual `subprojects` block described in `docs/v0.8.0.md` — the vanniktech plugin
handles POM metadata, signing, and sources/javadoc jars per-module via its DSL.

### `musicmeta-core/build.gradle.kts`

Replace the current `maven-publish` block and add the vanniktech plugin:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.publish)    // replaces `maven-publish`
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.landofoz", "musicmeta-core", "0.8.0")
    pom { /* same structure as musicmeta-okhttp above */ }
}
```

The `java { withSourcesJar(); withJavadocJar() }` block mentioned in `docs/v0.8.0.md` is NOT
needed — the vanniktech plugin adds these automatically for Kotlin JVM targets.

### `musicmeta-android/build.gradle.kts`

The Android module requires the `AndroidSingleVariantLibrary` configure block:
```kotlin
plugins {
    // existing plugins ...
    alias(libs.plugins.vanniktech.publish)    // replaces `maven-publish`
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )
    coordinates("com.landofoz", "musicmeta-android", "0.8.0")
    pom { /* same structure */ }
}
```

The existing `afterEvaluate { publishing { ... } }` block is removed — the vanniktech plugin
handles variant timing internally.

The `android { publishing { singleVariant("release") { ... } } }` block described in
`docs/v0.8.0.md` is NOT needed — `AndroidSingleVariantLibrary` in the vanniktech DSL replaces it.

---

## Credentials Setup

Goes in `~/.gradle/gradle.properties` (never committed):
```properties
mavenCentralUsername=<Central Portal user token username>
mavenCentralPassword=<Central Portal user token password>
signing.keyId=<last 8 chars of GPG key ID>
signing.password=<GPG passphrase>
signing.secretKeyRingFile=</path/to/secring.gpg>
```

Add comment-only placeholders to the project's `gradle.properties` so the setup is discoverable.

---

## Alternatives Considered

| Recommended | Alternative | Why Not |
|-------------|-------------|---------|
| OkHttp 4.12.0 | OkHttp 5.3.2 | Forces Kotlin 2.2.x stdlib onto consumers; MockWebServer coordinate change adds churn; upgrade belongs with a future Kotlin 2.2 bump |
| vanniktech maven-publish 0.36.0 | Raw `maven-publish` + OSSRH URLs | OSSRH shutdown June 2025; raw plugin has no Central Portal support; requires manual POM, signing, and sources jar config |
| vanniktech maven-publish 0.36.0 | JReleaser | JReleaser is a full CI/CD release pipeline tool; heavier than needed for a Gradle library publishing setup |
| vanniktech maven-publish 0.36.0 | OSSRH Staging API compatibility shim | Works but requires Portal token anyway; adds an indirection layer with no upside; the shim is a migration bridge, not a long-term solution |

---

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| OSSRH URLs (`s01.oss.sonatype.org`, `oss.sonatype.org`) | Shutdown June 30, 2025; uploads rejected | `SonatypeHost.CENTRAL_PORTAL` via vanniktech plugin |
| OkHttp 5.x for this milestone | Forces Kotlin 2.2.x stdlib onto library consumers; MockWebServer artifact rename | OkHttp 4.12.0 |
| `mockwebserver3-junit4` artifact | That is the OkHttp 5.x artifact; incompatible with OkHttp 4.12 | `com.squareup.okhttp3:mockwebserver:4.12.0` |
| `java { withSourcesJar(); withJavadocJar() }` | Duplicates what vanniktech plugin does automatically | Remove when applying vanniktech plugin |
| `android { publishing { singleVariant() } }` | Replaced by `AndroidSingleVariantLibrary` in vanniktech DSL | `configure(AndroidSingleVariantLibrary(...))` |
| `afterEvaluate { publishing { ... } }` in android module | Vanniktech plugin handles variant timing internally | Remove block; use plugin DSL |

---

## Version Compatibility Matrix

| Dependency | Version | Compatible With | Notes |
|------------|---------|-----------------|-------|
| `okhttp` | 4.12.0 | Kotlin 2.1.0, Java 17 | Compiled with Kotlin 1.9; fully binary-compatible with Kotlin 2.1 consumers |
| `mockwebserver` | 4.12.0 | JUnit 4.13.2 | Same group/artifact as OkHttp 4.x; no separate `-junit4` artifact needed in 4.x |
| `vanniktech.maven.publish` | 0.36.0 | Gradle 8.x, AGP 8.7.x, Kotlin 2.1.x | 0.36.0 requires min JDK upgrade; dropped Dokka v1 support (not used here) |
| `kotlinx-coroutines-core` | 1.9.0 (existing) | Kotlin 2.1.0 | `Flow` and `flow {}` builder included; no new dep needed for bulk enrichment |

---

## Corrections to `docs/v0.8.0.md`

Two issues in the implementation plan require changes:

### Issue 1: OkHttp version — version is correct, no change needed

`docs/v0.8.0.md` line 19 specifies `okhttp = "4.12.0"`. **This is correct.** Research confirms
4.12.0 is the right choice for this milestone. No change to the plan.

### Issue 2: Maven Central publishing approach — HIGH priority, plan must change

`docs/v0.8.0.md` Phase 4 describes:
- A `subprojects` block in root `build.gradle.kts` with OSSRH repository URLs
- `java { withSourcesJar(); withJavadocJar() }` per JVM module
- `android { publishing { singleVariant("release") { ... } } }` for the Android module
- Inline signing plugin configuration

**All of this is the OSSRH approach, which is shutdown.** The entire Phase 4 implementation must
be replaced with the vanniktech plugin approach documented in this file.

Specific line-level changes to the plan:
- Phase 4 "Shared publishing config" block → replace with vanniktech plugin applied per-module
- Phase 4 "Per-module changes" → replace `java { withSourcesJar()... }` with vanniktech DSL
- Phase 4 android module line → replace `android { publishing { singleVariant() } }` with
  `configure(AndroidSingleVariantLibrary(...))`
- Add `alias(libs.plugins.vanniktech.publish)` to version catalog and all three module build files

### Issue 3: MockWebServer artifact name — no change needed

`docs/v0.8.0.md` references `libs.okhttp.mockwebserver` which maps to
`com.squareup.okhttp3:mockwebserver:4.12.0`. This is the correct 4.x artifact name.
The `mockwebserver3-junit4` artifact only applies to OkHttp 5.x and should not be used.

---

## Sources

- [Maven Central: com.squareup.okhttp3:okhttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) — version 5.3.2 confirmed latest stable; HIGH confidence
- [OkHttp 5.x Changelog](https://square.github.io/okhttp/changelogs/changelog/) — 5.0.0 stable July 2025, Kotlin 2.2.x requirement confirmed; HIGH confidence
- [OkHttp 4.x Changelog](https://square.github.io/okhttp/changelogs/changelog_4x/) — 4.12.0 confirmed final 4.x release (Oct 2023); HIGH confidence
- [OkHttp GitHub README](https://github.com/square/okhttp) — recommends 5.3.0 for new projects; HIGH confidence
- [Sonatype OSSRH Sunset Announcement](https://central.sonatype.org/news/20250326_ossrh_sunset/) — OSSRH EOL June 30, 2025; HIGH confidence
- [Sonatype OSSRH EOL Page](https://central.sonatype.org/pages/ossrh-eol/) — shutdown confirmed, compatibility shim exists; HIGH confidence
- [vanniktech plugin GitHub Releases](https://github.com/vanniktech/gradle-maven-publish-plugin/releases) — 0.36.0 latest stable (Jan 18, 2025); HIGH confidence
- [vanniktech plugin Central Portal docs](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) — `CENTRAL_PORTAL` host config confirmed; HIGH confidence
- [vanniktech plugin "What to publish" docs](https://vanniktech.github.io/gradle-maven-publish-plugin/what/) — `AndroidSingleVariantLibrary`, `KotlinJvm` DSL config confirmed; HIGH confidence

---
*Stack research for: musicmeta v0.8.0 production readiness — OkHttp adapter, stale cache, bulk enrichment, Maven Central publishing*
*Researched: 2026-03-24*
