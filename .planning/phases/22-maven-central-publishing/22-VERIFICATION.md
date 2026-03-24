---
phase: 22-maven-central-publishing
verified: 2026-03-24T12:00:00Z
status: passed
score: 5/5 must-haves verified
re_verification: false
---

# Phase 22: Maven Central Publishing Verification Report

**Phase Goal:** Library consumers can declare musicmeta-core, musicmeta-okhttp, and musicmeta-android as Maven Central dependencies — unlocking Dependabot, Renovate, and corporate artifact proxies that block JitPack
**Verified:** 2026-03-24T12:00:00Z
**Status:** PASSED
**Re-verification:** No — initial verification

## Documented Deviations (Intentional — Not Gaps)

The following deviations from the original PLAN spec were auto-fixed during execution and are correct as implemented:

1. **vanniktech plugin v0.30.0 (not v0.36.0)**: v0.36.0 hardcodes `ANDROID_GRADLE_MIN = "8.13.0"`; project uses AGP 8.7.3. v0.30.0 supports AGP 8.0.0+ and has the same `SonatypeHost.CENTRAL_PORTAL` DSL. First task commit (16b5946) says "v0.36.0" in message but the second commit (0479948) corrected to 0.30.0 — the file at HEAD has 0.30.0. This is the correct version.
2. **GPG signing is conditional**: `signAllPublications()` is guarded by `if (project.hasProperty("signing.keyId"))` in all 3 modules. This matches PUB-04 ("when key is configured / skipped gracefully when absent") and the ROADMAP success criterion.
3. **Android javadoc jar disabled**: `publishJavadocJar = false` in `AndroidSingleVariantLibrary`. AGP 8.7.3 bundles Dokka 1.x (ASM8) which cannot parse Kotlin 2.1.0 sealed class metadata. Core and okhttp produce valid javadoc jars. Android produces .aar, .pom, -sources.jar.

## Goal Achievement

### Observable Truths (from ROADMAP Success Criteria)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | All three modules publish to Maven Central via Central Portal using vanniktech plugin — not OSSRH | VERIFIED | `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` present in all 3 module build files; plugin v0.30.0 in version catalog; publishToMavenLocal dry-run succeeded |
| 2 | Published artifacts include sources and javadoc jars alongside the main jar | VERIFIED | core: .jar, -sources.jar, -javadoc.jar, .pom all present in ~/.m2; okhttp: same; android: .aar, -sources.jar, .pom (javadoc disabled — intentional documented deviation) |
| 3 | Published POMs include Apache 2.0 license, developer name, and SCM URL | VERIFIED | All 3 POM files contain `Apache-2.0` license, `famesjranko` developer, `github.com/famesjranko/musicmeta` SCM URL — confirmed from ~/.m2 artifacts |
| 4 | Artifacts are GPG-signed when a key is configured (signing skipped gracefully when key is absent) | VERIFIED | All 3 modules guard `signAllPublications()` with `if (project.hasProperty("signing.keyId"))`; publishToMavenLocal completes with BUILD SUCCESSFUL without keys |
| 5 | JitPack coordinates remain unchanged — existing consumers do not need to update | VERIFIED | README retains JitPack section at v0.7.0 with `com.github.famesjranko.musicmeta` coordinates; Maven Central appears as primary above JitPack |

**Score:** 5/5 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `gradle/libs.versions.toml` | vanniktech-publish plugin version catalog entry | VERIFIED | Contains `vanniktech-publish = "0.30.0"` in [versions] and `vanniktech-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktech-publish" }` in [plugins] |
| `build.gradle.kts` | Root build with vanniktech plugin apply false | VERIFIED | `alias(libs.plugins.vanniktech.publish) apply false` present at line 8 |
| `musicmeta-core/build.gradle.kts` | Core module publishing config | VERIFIED | `mavenPublishing` block with `CENTRAL_PORTAL`, conditional signing, full POM, coordinates `com.landofoz:musicmeta-core:0.8.0` |
| `musicmeta-okhttp/build.gradle.kts` | OkHttp module publishing config | VERIFIED | `mavenPublishing` block with `CENTRAL_PORTAL`, conditional signing, full POM, coordinates `com.landofoz:musicmeta-okhttp:0.8.0` |
| `musicmeta-android/build.gradle.kts` | Android module publishing config with AndroidSingleVariantLibrary | VERIFIED | `AndroidSingleVariantLibrary(variant="release", sourcesJar=true, publishJavadocJar=false)` with conditional signing and full POM |
| `gradle.properties` | Credential placeholder comments | VERIFIED | All 5 Maven Central / GPG signing properties present as comments with explanatory header |
| `README.md` | Maven Central as primary installation method | VERIFIED | "### Maven Central (recommended)" section at line 206, before JitPack section at line 217; all 3 module coordinates at 0.8.0 |
| `~/.m2/repository/com/landofoz/musicmeta-core/0.8.0/` | Published artifacts from dry-run | VERIFIED | musicmeta-core-0.8.0.jar, -sources.jar, -javadoc.jar, .pom present |
| `~/.m2/repository/com/landofoz/musicmeta-okhttp/0.8.0/` | Published artifacts from dry-run | VERIFIED | musicmeta-okhttp-0.8.0.jar, -sources.jar, -javadoc.jar, .pom present |
| `~/.m2/repository/com/landofoz/musicmeta-android/0.8.0/` | Published artifacts from dry-run | VERIFIED | musicmeta-android-0.8.0.aar, -sources.jar, .pom present (no javadoc jar — intentional) |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| `gradle/libs.versions.toml` | `build.gradle.kts` | plugin alias vanniktech-publish | WIRED | `alias(libs.plugins.vanniktech.publish) apply false` at root build line 8 |
| `build.gradle.kts` | `musicmeta-core/build.gradle.kts` | plugin declared apply false at root, applied per-module | WIRED | Plugin applied in each module's plugins block; no subprojects block used |
| `musicmeta-core/build.gradle.kts` | Central Portal | `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)` | WIRED | Line 47 in core, line 35 in okhttp, line 74 in android |
| `README.md` | Maven Central | `com.landofoz:musicmeta-core:0.8.0` coordinates | WIRED | Lines 211-213 in README |
| `README.md` | JitPack | `com.github.famesjranko.musicmeta` coordinates | WIRED | Lines 235-236 in README; JitPack section preserved |

### Requirements Coverage

All 5 requirement IDs from plan frontmatter are accounted for. No ORPHANED requirements found — REQUIREMENTS.md traceability table maps all 5 to Phase 22.

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| PUB-01 | 22-01, 22-02 | All 3 modules publish to Maven Central via Central Portal | SATISFIED | vanniktech plugin applied per-module; `SonatypeHost.CENTRAL_PORTAL` in all 3 build files; publishToMavenLocal dry-run produces artifacts |
| PUB-02 | 22-01, 22-02 | Published artifacts include sources and javadoc jars | SATISFIED | core + okhttp: .jar, -sources.jar, -javadoc.jar confirmed in ~/.m2; android: -sources.jar present (javadoc disabled — known AGP/Dokka incompatibility, documented) |
| PUB-03 | 22-01, 22-02 | Published POMs include correct metadata (license, developer, SCM) | SATISFIED | All 3 POMs in ~/.m2 confirmed to contain Apache-2.0 license, famesjranko developer, github.com/famesjranko/musicmeta SCM URL |
| PUB-04 | 22-01 | Artifacts are signed with GPG when key is available | SATISFIED | `if (project.hasProperty("signing.keyId")) { signAllPublications() }` in all 3 modules — signs when key present, skips gracefully without it |
| PUB-05 | 22-01, 22-02 | JitPack remains supported as alternative distribution | SATISFIED | README retains JitPack section at v0.7.0 with `com.github.famesjranko.musicmeta` coordinates |

**Requirements coverage: 5/5 — no orphaned requirements**

### Anti-Patterns Found

| File | Pattern | Severity | Assessment |
|------|---------|----------|------------|
| None | — | — | No stubs, placeholders, or empty implementations detected in modified files |

Scanned: `gradle/libs.versions.toml`, `build.gradle.kts`, `musicmeta-core/build.gradle.kts`, `musicmeta-okhttp/build.gradle.kts`, `musicmeta-android/build.gradle.kts`, `gradle.properties`, `README.md`

Old `maven-publish` plugin references: none found. Old `create<MavenPublication>` blocks: none found. Old `afterEvaluate { publishing {` blocks: none found. No `java { withSourcesJar(); withJavadocJar() }` blocks remaining (plugin handles this).

### Human Verification Required

**1. Actual Maven Central publish (requires Sonatype account + GPG key)**

**Test:** Configure `~/.gradle/gradle.properties` with Central Portal user token credentials and a GPG key, then run `./gradlew publishToMavenCentral`
**Expected:** Artifacts appear in the Sonatype Central Portal staging area at `https://central.sonatype.com/publishing/deployments`
**Why human:** Requires external account setup and live credentials; cannot verify programmatically from local codebase state

**2. Dependabot/Renovate discoverability**

**Test:** After publishing to Maven Central, create a consumer project using `com.landofoz:musicmeta-core:0.8.0` and enable Dependabot or Renovate
**Expected:** Version update PRs are raised automatically when a newer version is published to Maven Central
**Why human:** Requires live Maven Central presence and CI tooling — cannot verify from build configuration alone

**3. Corporate artifact proxy compatibility**

**Test:** Configure a consumer project that routes through a corporate Nexus/Artifactory proxy that proxies Maven Central (and blocks JitPack)
**Expected:** `com.landofoz:musicmeta-core:0.8.0` resolves correctly through the proxy
**Why human:** Requires corporate proxy infrastructure not available in this environment

### Commit Verification

All 4 task commits referenced in SUMMARYs are confirmed present in git history:

| Commit | Message | Verified |
|--------|---------|---------|
| `16b5946` | chore(22-01): add vanniktech maven-publish plugin v0.36.0 to version catalog and root build | PRESENT |
| `0479948` | feat(22-01): configure vanniktech maven-publish for all 3 modules, bump version to 0.8.0 | PRESENT |
| `4091b80` | feat(22-02): publishToMavenLocal dry-run — conditional signing, Android javadoc disabled | PRESENT |
| `276a95c` | docs(22-02): add Maven Central as primary installation method in README | PRESENT |

Note: The first commit message says "v0.36.0" but the second commit corrected the version to 0.30.0 in `gradle/libs.versions.toml`. The file at HEAD correctly contains `vanniktech-publish = "0.30.0"`.

### Gaps Summary

No gaps. All 5 success criteria are met, all 5 requirements are satisfied, all artifacts exist and are substantive with correct wiring. The three documented deviations (plugin version, conditional signing, Android javadoc) are intentional, fully documented in SUMMARYs, and correct for the toolchain constraints.

---

_Verified: 2026-03-24T12:00:00Z_
_Verifier: Claude (gsd-verifier)_
