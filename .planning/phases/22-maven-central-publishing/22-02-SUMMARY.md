---
phase: 22-maven-central-publishing
plan: 02
subsystem: infra
tags: [maven-central, gradle, vanniktech, publishing, readme, documentation, dry-run]

# Dependency graph
requires:
  - phase: 22-01
    provides: vanniktech plugin applied to all 3 modules with CENTRAL_PORTAL target + POM metadata
provides:
  - publishToMavenLocal dry-run verified for all 3 modules (core, okhttp, android)
  - Conditional signing (only when signing.keyId present) in all 3 modules
  - README updated with Maven Central as primary installation method
  - musicmeta-okhttp coordinates documented in README for the first time
  - JitPack coordinates preserved for existing consumers (PUB-05 satisfied)
  - Maven Local version reference updated to 0.8.0
affects: [phase-23-release, any future publishing or documentation tasks]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - Conditional signing: if (project.hasProperty("signing.keyId")) { signAllPublications() }
    - AndroidSingleVariantLibrary with publishJavadocJar = false (AGP 8.7.3 Dokka incompatibility)

key-files:
  created: []
  modified:
    - musicmeta-core/build.gradle.kts
    - musicmeta-okhttp/build.gradle.kts
    - musicmeta-android/build.gradle.kts
    - README.md

key-decisions:
  - "Conditional signing: signAllPublications() requires GPG keys; dry-run without keys fails unless guarded by hasProperty('signing.keyId')"
  - "Android javadoc jar disabled: AGP 8.7.3 bundles Dokka 1.x which expects Kotlin metadata 1.4.2 but project uses 2.1.0; manifests as ASM9 error on sealed classes from musicmeta-core"
  - "publishJavadocJar = false for Android only: core and okhttp produce valid javadoc jars via standard javadoc task; Android limitation is Dokka version pinned by AGP"

patterns-established:
  - "Conditional signing pattern: wrap signAllPublications() in hasProperty check for local publish compatibility"

requirements-completed: [PUB-01, PUB-02, PUB-03, PUB-05]

# Metrics
duration: 5min
completed: 2026-03-24
---

# Phase 22 Plan 02: Dry-run Verification and README Update Summary

**publishToMavenLocal verified for all 3 modules with correct POM metadata; README restructured with Maven Central as primary installation method including musicmeta-okhttp coordinates for the first time**

## Performance

- **Duration:** 5 min
- **Started:** 2026-03-24T11:43:55Z
- **Completed:** 2026-03-24T11:48:49Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments
- publishToMavenLocal succeeds for all 3 modules with BUILD SUCCESSFUL
- All 3 POM files contain Apache-2.0 license, famesjranko developer entry, and GitHub SCM URLs
- core and okhttp produce .jar, -sources.jar, -javadoc.jar, and .pom
- android produces .aar, -sources.jar, and .pom
- README restructured: Maven Central is primary installation method with all 3 module coordinates
- musicmeta-okhttp added to README for the first time (existed since Phase 19 but not documented)
- JitPack section preserved at v0.7.0 for existing consumers (PUB-05)

## Task Commits

Each task was committed atomically:

1. **Task 1: Verify publishToMavenLocal produces correct artifacts** - `4091b80` (feat)
2. **Task 2: Update README with Maven Central installation and OkHttp coordinates** - `276a95c` (docs)

**Plan metadata:** (see final docs commit)

## Files Created/Modified
- `musicmeta-core/build.gradle.kts` - Conditional signing (hasProperty guard)
- `musicmeta-okhttp/build.gradle.kts` - Conditional signing (hasProperty guard)
- `musicmeta-android/build.gradle.kts` - Conditional signing + publishJavadocJar = false
- `README.md` - Maven Central as primary install; JitPack as fallback; musicmeta-okhttp added; Maven Local version 0.8.0; Quick Start updated

## Decisions Made
- **Conditional signing**: The plan noted "signAllPublications() call succeeding without error when no key is present" — this assertion was incorrect. Gradle signing plugin fails hard when no signatory is configured. Fix: guard with `if (project.hasProperty("signing.keyId"))` so local publishes work without GPG keys, while CI/CD with keys still gets signed artifacts.
- **Android javadoc jar disabled**: AGP 8.7.3 bundles Dokka 1.x (ASM8) which cannot parse Java 17 sealed classes. The error is `PermittedSubclasses requires ASM9` and `Kotlin binary version 2.1.0, expected 1.4.2`. This is a fundamental incompatibility — not fixable without upgrading AGP. Setting `publishJavadocJar = false` is the correct workaround until AGP upgrades to a version bundling Dokka 2.x.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Conditional signing to allow publishToMavenLocal without GPG keys**
- **Found during:** Task 1 (publishToMavenLocal dry-run)
- **Issue:** `signAllPublications()` fails with "Cannot perform signing task because it has no configured signatory" when no GPG key is configured. The plan expected this to pass silently.
- **Fix:** Wrapped `signAllPublications()` in `if (project.hasProperty("signing.keyId"))` in all 3 module build files.
- **Files modified:** musicmeta-core/build.gradle.kts, musicmeta-okhttp/build.gradle.kts, musicmeta-android/build.gradle.kts
- **Verification:** publishToMavenLocal completes with BUILD SUCCESSFUL
- **Committed in:** `4091b80` (Task 1 commit)

**2. [Rule 3 - Blocking] Disabled Android javadoc jar due to AGP-bundled Dokka incompatibility**
- **Found during:** Task 1 (publishToMavenLocal dry-run)
- **Issue:** AGP 8.7.3 bundles Dokka 1.x (ASM8). `javaDocReleaseGeneration` task fails with `PermittedSubclasses requires ASM9` when processing sealed classes from musicmeta-core compiled with Kotlin 2.1.0 (metadata version 2.1.0, Dokka expects 1.4.2).
- **Fix:** Set `publishJavadocJar = false` in `AndroidSingleVariantLibrary` for musicmeta-android. Added comment explaining the issue and condition for re-enabling.
- **Files modified:** musicmeta-android/build.gradle.kts
- **Verification:** publishToMavenLocal completes with BUILD SUCCESSFUL; android produces .aar, .pom, -sources.jar
- **Committed in:** `4091b80` (Task 1 commit)

---

**Total deviations:** 2 auto-fixed (2 blocking)
**Impact on plan:** Both fixes required for the dry-run to succeed. The Android javadoc jar limitation is a known AGP/Dokka toolchain issue, not a publishing config error. Signing remains operative for CI/CD with GPG keys. Core and okhttp modules produce full artifact sets including javadoc.

## Issues Encountered
- AGP 8.7.3 bundles Dokka 1.x: cannot generate Android javadoc until AGP is upgraded to a release bundling Dokka 2.x. This is pre-existing, not introduced by Phase 22.
- vanniktech signing contract: the plugin does not gracefully handle missing signing config — it requires an explicit property guard in build files.

## Known Stubs

None — all publishing configuration is real; artifacts contain correct metadata.

## User Setup Required

**External services require manual configuration before publishing to Maven Central:**

1. **Sonatype Central Portal account**: Create account at https://central.sonatype.com, register `com.landofoz` namespace
2. **Generate user token**: https://central.sonatype.com/account -> Generate User Token
3. **GPG key**: Generate and upload to a public keyserver (`gpg --gen-key`, `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`)
4. **Add to `~/.gradle/gradle.properties`** (never committed):
   ```properties
   mavenCentralUsername=<token username>
   mavenCentralPassword=<token password>
   signing.keyId=<last 8 chars of GPG key ID>
   signing.password=<GPG passphrase>
   signing.secretKeyRingFile=<path to secring.gpg>
   ```

## Next Phase Readiness
- publishToMavenLocal dry-run passes for all 3 modules
- POM metadata verified: Apache-2.0, famesjranko developer, SCM URL in all 3 modules
- README documents Maven Central as primary installation method (v0.8.0)
- Blocker: Central Portal namespace `com.landofoz` must be registered and verified before first real publish
- Blocker: GPG key must exist and credentials in `~/.gradle/gradle.properties` before `publishToMavenCentral`
- Android javadoc: will require AGP upgrade to re-enable (currently disabled)

---
*Phase: 22-maven-central-publishing*
*Completed: 2026-03-24*
