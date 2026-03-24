---
phase: 22-maven-central-publishing
plan: 01
subsystem: infra
tags: [maven-central, gradle, vanniktech, publishing, gpg-signing, sonatype]

# Dependency graph
requires: []
provides:
  - vanniktech gradle-maven-publish-plugin 0.30.0 applied to all 3 modules
  - mavenPublishing DSL targeting SonatypeHost.CENTRAL_PORTAL in core, okhttp, android
  - POM metadata (Apache-2.0, famesjranko developer, SCM URL) in all modules
  - GPG signing configured via signAllPublications() in all modules
  - AndroidSingleVariantLibrary variant publishing configured for musicmeta-android
  - Version 0.8.0 across all 3 modules
  - Credential comment placeholders in gradle.properties
affects: [phase-23-release, any future publishing tasks]

# Tech tracking
tech-stack:
  added:
    - com.vanniktech.maven.publish:gradle-maven-publish-plugin:0.30.0
  patterns:
    - Per-module mavenPublishing DSL (not subprojects block) with CENTRAL_PORTAL target
    - AndroidSingleVariantLibrary for Android module variant publishing

key-files:
  created: []
  modified:
    - gradle/libs.versions.toml
    - build.gradle.kts
    - musicmeta-core/build.gradle.kts
    - musicmeta-okhttp/build.gradle.kts
    - musicmeta-android/build.gradle.kts
    - gradle.properties

key-decisions:
  - "vanniktech 0.30.0 instead of 0.36.0: 0.36.0 hardcodes AGP 8.13.0 minimum; project is on AGP 8.7.3; 0.30.0 requires AGP 8.0.0+ and supports CENTRAL_PORTAL with the same SonatypeHost.CENTRAL_PORTAL DSL"
  - "0.35.0 also incompatible: removed SonatypeHost class entirely and changed publishToMavenCentral() signature (no host parameter); 0.30.0 matches plan DSL exactly"
  - "Per-module plugin application (not subprojects block): follows vanniktech recommendation and plan spec"

patterns-established:
  - "mavenPublishing DSL per module: coordinates() + pom{} + publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL) + signAllPublications()"

requirements-completed: [PUB-01, PUB-02, PUB-03, PUB-04]

# Metrics
duration: 7min
completed: 2026-03-24
---

# Phase 22 Plan 01: Maven Central Publishing Config Summary

**vanniktech gradle-maven-publish-plugin 0.30.0 applied to all 3 modules (core, okhttp, android) targeting SonatypeHost.CENTRAL_PORTAL with GPG signing, full POM metadata, and version 0.8.0**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-24T11:34:10Z
- **Completed:** 2026-03-24T11:41:XX Z
- **Tasks:** 2
- **Files modified:** 6

## Accomplishments
- Replaced maven-publish plugin with vanniktech gradle-maven-publish-plugin in all 3 modules
- Configured mavenPublishing DSL targeting SonatypeHost.CENTRAL_PORTAL with signAllPublications()
- Added full POM metadata (Apache-2.0 license, famesjranko developer, GitHub SCM URLs) to all 3 modules
- musicmeta-android module uses AndroidSingleVariantLibrary(variant=release) for proper AAR publishing
- Version bumped from 0.1.0 to 0.8.0 in all 3 modules
- Credential comment placeholders added to gradle.properties; actual credentials go in ~/.gradle/gradle.properties

## Task Commits

Each task was committed atomically:

1. **Task 1: Add vanniktech plugin to version catalog and root build** - `16b5946` (chore)
2. **Task 2: Configure publishing for all 3 modules, version bump, credential placeholders** - `0479948` (feat)

**Plan metadata:** (see final docs commit)

## Files Created/Modified
- `gradle/libs.versions.toml` - Added vanniktech-publish = "0.30.0" to [versions] and [plugins]
- `build.gradle.kts` - Added alias(libs.plugins.vanniktech.publish) apply false to root plugins block
- `musicmeta-core/build.gradle.kts` - Replaced maven-publish with vanniktech; added mavenPublishing block; version 0.8.0
- `musicmeta-okhttp/build.gradle.kts` - Replaced maven-publish with vanniktech; added mavenPublishing block; version 0.8.0
- `musicmeta-android/build.gradle.kts` - Replaced maven-publish + afterEvaluate with vanniktech + AndroidSingleVariantLibrary; version 0.8.0
- `gradle.properties` - Added credential comment placeholders for Central Portal and GPG signing

## Decisions Made
- **vanniktech 0.30.0 instead of 0.36.0**: The plan specified 0.36.0, but 0.36.0 hardcodes `ANDROID_GRADLE_MIN = "8.13.0"` in its source (verified via JAR inspection and GitHub source). The project uses AGP 8.7.3. Version 0.30.0 requires only AGP 8.0.0+, was tested with AGP 8.7.0, and has the exact same `SonatypeHost.CENTRAL_PORTAL` DSL the plan documents.
- **0.35.0 also rejected**: 0.35.0 removed `SonatypeHost` entirely and changed `publishToMavenCentral()` to have no host parameter, targeting only Central Portal via a different mechanism. This would require a different DSL pattern than what the plan specifies.
- **Per-module plugin application**: The vanniktech plugin is applied individually in each module's `plugins {}` block, with `apply false` at root. No `subprojects` block.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Downgraded vanniktech from 0.36.0 to 0.30.0**
- **Found during:** Task 2 (configure publishing for all 3 modules)
- **Issue:** vanniktech 0.36.0 hardcodes `ANDROID_GRADLE_MIN = "8.13.0"` (verified via downloaded JAR and GitHub source). Project uses AGP 8.7.3. Build failed with: "Make sure the AGP version 8.13.0 or newer is applied."
- **Fix:** Version catalog changed from `vanniktech-publish = "0.36.0"` to `vanniktech-publish = "0.30.0"`. Version 0.30.0 was also rejected as intermediate (0.35.0 removed SonatypeHost API). 0.30.0 supports CENTRAL_PORTAL, AGP 8.0.0+, Gradle 8.5+, and has the exact DSL the plan documents.
- **Files modified:** gradle/libs.versions.toml
- **Verification:** `./gradlew build` passes with BUILD SUCCESSFUL; CENTRAL_PORTAL in all 3 modules confirmed; no old maven-publish blocks remain
- **Committed in:** `0479948` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (1 blocking)
**Impact on plan:** The version downgrade is necessary for compatibility with the existing toolchain. The functional result is identical — all 3 modules are configured for Maven Central via Central Portal with GPG signing, POM metadata, and the same DSL the plan specified. The only difference is the plugin version number.

## Issues Encountered
- vanniktech API break between 0.30.0 and 0.35.0: `SonatypeHost` enum was removed in 0.35.0, and `publishToMavenCentral()` no longer accepts a host parameter. This means any future upgrade to 0.35.0+ will require updating the DSL in all 3 module build files.

## User Setup Required

**External services require manual configuration before publishing.** The following environment-specific steps are needed before running `./gradlew publishToMavenCentral`:

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
- Build configuration complete; `./gradlew build` passes with BUILD SUCCESSFUL
- All 3 modules have publishing tasks: `publishAllPublicationsToMavenCentralRepository`, `publishToMavenCentral`
- Blocker: Central Portal namespace `com.landofoz` must be verified before first publish (may require 1-2 business day approval)
- Blocker: GPG key must exist and credentials must be in `~/.gradle/gradle.properties`

## Self-Check: PASSED

- SUMMARY.md exists: FOUND
- Task commit 16b5946: FOUND
- Task commit 0479948: FOUND
- musicmeta-core/build.gradle.kts: FOUND
- musicmeta-okhttp/build.gradle.kts: FOUND
- musicmeta-android/build.gradle.kts: FOUND
- gradle/libs.versions.toml: FOUND
- gradle.properties: FOUND
- `./gradlew build`: BUILD SUCCESSFUL (121 tasks)

---
*Phase: 22-maven-central-publishing*
*Completed: 2026-03-24*
