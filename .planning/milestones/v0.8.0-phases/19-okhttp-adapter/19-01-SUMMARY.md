---
phase: 19-okhttp-adapter
plan: 01
subsystem: http

tags: [okhttp, kotlin, jvm, gradle, http-client]

# Dependency graph
requires: []
provides:
  - musicmeta-okhttp Gradle module with OkHttp 4.12.0 dependency
  - OkHttpEnrichmentClient implementing all 10 HttpClient methods
  - OkHttp and okhttp-mockwebserver version catalog entries
affects: [19-02-PLAN, musicmeta-android]

# Tech tracking
tech-stack:
  added: [okhttp 4.12.0, okhttp-mockwebserver 4.12.0]
  patterns:
    - parseJsonResult<T> generic helper factors out status-code mapping and JSON parsing for all 4 HttpResult methods
    - noRedirectClient lazy-initialized via newBuilder().followRedirects(false) for fetchRedirectUrl

key-files:
  created:
    - musicmeta-okhttp/build.gradle.kts
    - musicmeta-okhttp/src/main/kotlin/com/landofoz/musicmeta/okhttp/OkHttpEnrichmentClient.kt
  modified:
    - gradle/libs.versions.toml
    - settings.gradle.kts

key-decisions:
  - "OkHttp 4.12.0 (not 5.x): avoids Kotlin 2.2.x stdlib conflict for library consumers"
  - "No Accept-Encoding header: OkHttp transparent gzip decompression would break if header set manually"
  - "parseJsonResult<T> generic helper: avoids 4x duplication of status-code branching across fetchJsonResult/fetchJsonArrayResult/postJsonResult/postJsonArrayResult"

patterns-established:
  - "parseJsonResult<T>(response, parse): maps status codes to HttpResult variants and applies parse lambda on 2xx body"

requirements-completed: [HTTP-01, HTTP-02, HTTP-03, HTTP-04, HTTP-05]

# Metrics
duration: 3min
completed: 2026-03-24
---

# Phase 19 Plan 01: OkHttp Adapter — Module Scaffold and Implementation Summary

**musicmeta-okhttp module with OkHttpEnrichmentClient implementing all 10 HttpClient methods via OkHttp 4.12.0 Call API, transparent gzip, and correct 429/4xx/5xx status mapping**

## Performance

- **Duration:** ~3 min
- **Started:** 2026-03-24T10:46:54Z
- **Completed:** 2026-03-24T10:50:00Z
- **Tasks:** 2
- **Files modified:** 4 (2 created, 2 modified)

## Accomplishments

- Created `musicmeta-okhttp` Gradle module with `api(project(":musicmeta-core"))` and `api(libs.okhttp)` so consumers can configure their own OkHttpClient
- Implemented `OkHttpEnrichmentClient` with all 10 `HttpClient` methods: 6 nullable returns and 4 typed `HttpResult` returns
- Status code mapping: 429 -> `RateLimited` (with `Retry-After` ms conversion), 400-499 -> `ClientError`, 500-599 -> `ServerError`, 2xx -> `Ok`
- Zero `Accept-Encoding` header set — OkHttp handles gzip transparently
- `noRedirectClient` via `newBuilder().followRedirects(false)` for `fetchRedirectUrl`
- Generic `parseJsonResult<T>` helper eliminates duplication across 4 `HttpResult` methods

## Task Commits

Each task was committed atomically:

1. **Task 1: Create musicmeta-okhttp Gradle module with version catalog entries** - `c1a9690` (chore)
2. **Task 2: Implement OkHttpEnrichmentClient with all 10 HttpClient methods** - `41f2ee7` (feat)

## Files Created/Modified

- `musicmeta-okhttp/build.gradle.kts` — Module config: api(core), api(okhttp), implementation(json+coroutines), testImplementation(mockwebserver)
- `musicmeta-okhttp/src/main/kotlin/com/landofoz/musicmeta/okhttp/OkHttpEnrichmentClient.kt` — 197 lines, all 10 HttpClient methods implemented
- `gradle/libs.versions.toml` — Added okhttp 4.12.0 version entry + okhttp and okhttp-mockwebserver library entries
- `settings.gradle.kts` — Added `include(":musicmeta-okhttp")`

## Decisions Made

- Used a generic `parseJsonResult<T>` helper to factor out the repeated status-code branching logic for the 4 `HttpResult` methods. The initial implementation used a `toHttpResult(response): HttpResult<String>` helper but ran into Kotlin type-inference issues with `else -> raw` in `when` expressions. The generic approach resolves the issue cleanly without casts.
- `noRedirectClient` is initialized at construction time via `client.newBuilder().followRedirects(false).build()` rather than lazily — cheap operation at client creation, safe for all usages.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Refactored toHttpResult helper to parseJsonResult<T> to fix Kotlin type-inference error**
- **Found during:** Task 2 (OkHttpEnrichmentClient compilation)
- **Issue:** Initial design used `toHttpResult(response): HttpResult<String>` and then `else -> raw` in `when` expressions inside each `HttpResult` method. Kotlin's type checker rejected `else -> raw` because `HttpResult<String>` is not assignable to `HttpResult<JSONObject>`/`HttpResult<JSONArray>` even though the non-`Ok` variants are `HttpResult<Nothing>` (covariant). The type checker couldn't infer this.
- **Fix:** Replaced `toHttpResult` with a generic `parseJsonResult<T>(response, parse: (String) -> T): HttpResult<T>` that applies the `parse` lambda directly inside the `200..299` branch, making the return type concrete throughout.
- **Files modified:** OkHttpEnrichmentClient.kt (revised before first commit of the file)
- **Verification:** `./gradlew :musicmeta-okhttp:compileKotlin` — BUILD SUCCESSFUL
- **Committed in:** `41f2ee7` (Task 2 commit)

---

**Total deviations:** 1 auto-fixed (Rule 1 - Bug)
**Impact on plan:** Design intent preserved. The helper still factors out status-code mapping; it just takes the parse lambda inline instead of doing a two-step string-then-parse approach.

## Issues Encountered

None beyond the type-inference compile error documented above.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

- `musicmeta-okhttp` module is built and `OkHttpEnrichmentClient` compiles with all 10 methods
- Plan 19-02 (tests for OkHttpEnrichmentClient with MockWebServer) can proceed immediately
- Core module tests still pass — no regressions

---
*Phase: 19-okhttp-adapter*
*Completed: 2026-03-24*
