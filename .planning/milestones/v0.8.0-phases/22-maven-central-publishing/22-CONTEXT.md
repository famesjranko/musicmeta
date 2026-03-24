# Phase 22: Maven Central Publishing - Context

**Gathered:** 2026-03-24
**Status:** Ready for planning

<domain>
## Phase Boundary

Configure Maven Central publishing for all 3 modules (musicmeta-core, musicmeta-okhttp, musicmeta-android) using the vanniktech gradle-maven-publish-plugin targeting Sonatype Central Portal. OSSRH is defunct (shut down June 2025). Add POM metadata, GPG signing (conditional), sources/javadoc jars. JitPack remains supported as alternative.

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion

All implementation choices are at Claude's discretion — pure infrastructure phase.

Key constraints from research:
- **OSSRH is dead** (shut down June 30, 2025) — do NOT use OSSRH URLs or credentials
- Use `com.vanniktech.maven.publish` plugin v0.36.0
- Target `SonatypeHost.CENTRAL_PORTAL` (not S01 or default)
- For core + okhttp: use `KotlinJvm` platform
- For android: use `AndroidSingleVariantLibrary("release")` platform
- POM metadata: Apache 2.0 license, developer name/email, SCM URL
- GPG signing: enabled when key present, skips gracefully when absent
- Credentials from gradle.properties or environment variables
- JitPack coordinates must remain unchanged
- Version bumped to 0.8.0 across all modules
- Add credential placeholders to gradle.properties with comments
- Update README.md installation section with Maven Central coordinates alongside JitPack
- The vanniktech plugin handles sources/javadoc jars automatically — no manual `withSourcesJar()`/`withJavadocJar()` needed
- The `afterEvaluate` block in musicmeta-android can potentially be simplified since vanniktech handles publication creation

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `build.gradle.kts` (root) — currently minimal, no subprojects block
- `musicmeta-core/build.gradle.kts` — has maven-publish with basic publication
- `musicmeta-android/build.gradle.kts` — has maven-publish inside afterEvaluate
- `musicmeta-okhttp/build.gradle.kts` — has maven-publish (just created in Phase 19)
- `gradle.properties` — has `org.gradle.jvmargs`, `android.useAndroidX`, `android.nonTransitiveRClass`

### Established Patterns
- Version "0.1.0" hardcoded in each module's build.gradle.kts
- maven-publish plugin with `create<MavenPublication>("maven")` pattern
- `components["java"]` for JVM modules, `components["release"]` for Android

### Integration Points
- `gradle/libs.versions.toml` — add vanniktech plugin
- `build.gradle.kts` (root) — apply vanniktech plugin to subprojects or per-module
- Per-module `build.gradle.kts` — configure `mavenPublishing {}` block
- `gradle.properties` — credential placeholders
- `README.md` — installation instructions

</code_context>

<specifics>
## Specific Ideas

No specific requirements — infrastructure phase. Research strongly recommends per-module plugin application over root subprojects block.

</specifics>

<deferred>
## Deferred Ideas

- BOM (Bill of Materials) artifact for coordinated version management
- CI/CD pipeline for automated publishing (GitHub Actions)
- Snapshot publishing for development builds

</deferred>
