---
phase: 03-public-api-cleanup
plan: 02
subsystem: api
tags: [kotlin, sealed-class, error-handling, http, enum]

# Dependency graph
requires:
  - phase: 01-bug-fixes
    provides: working provider implementations that construct EnrichmentResult.Error
provides:
  - ErrorKind enum (NETWORK, AUTH, PARSE, RATE_LIMIT, UNKNOWN) on EnrichmentResult.Error
  - HttpResult sealed class with typed HTTP response subtypes
  - fetchJsonResult() on HttpClient interface and DefaultHttpClient
  - FakeHttpClient.fetchJsonResult() support for test usage
affects: [04-new-enrichment-types, 05-deepen-coverage]

# Tech tracking
tech-stack:
  added: []
  patterns: [sealed-class error hierarchy, typed HTTP results, backward-compatible default parameters]

key-files:
  created:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/HttpResult.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/EnrichmentResultTest.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/http/DefaultHttpClientTest.kt
  modified:
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentResult.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/HttpClient.kt
    - musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/DefaultHttpClient.kt
    - musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/testutil/FakeHttpClient.kt

key-decisions:
  - "ErrorKind.UNKNOWN as default preserves all existing 4-arg Error construction sites without changes"
  - "fetchJsonResult has no retry logic -- providers handle retries themselves"
  - "JSON parse failures map to NetworkError since the HTTP body was malformed"
  - "readErrorBody helper reads errorStream for non-2xx responses"

patterns-established:
  - "Backward-compatible enum extension: new enum + default parameter on existing data class"
  - "HttpResult sealed class pattern for typed HTTP responses without null ambiguity"
  - "FakeHttpClient dual-mode: explicit givenHttpResult or auto-fallback to fetchJson"

requirements-completed: [API-04, API-05]

# Metrics
duration: 7min
completed: 2026-03-21
---

# Phase 03 Plan 02: Error Categorization Summary

**ErrorKind enum on EnrichmentResult.Error and HttpResult sealed class with typed HTTP responses via fetchJsonResult()**

## Performance

- **Duration:** 7 min
- **Started:** 2026-03-21T08:39:41Z
- **Completed:** 2026-03-21T08:47:00Z
- **Tasks:** 2
- **Files modified:** 7

## Accomplishments
- ErrorKind enum with 5 values (NETWORK, AUTH, PARSE, RATE_LIMIT, UNKNOWN) enables callers to handle error types programmatically without parsing message strings
- HttpResult sealed class with Ok, ClientError, ServerError, RateLimited, NetworkError gives providers precise HTTP status information
- fetchJsonResult() added to HttpClient interface and DefaultHttpClient with status-aware response handling (no retry logic, no ambiguous nulls)
- All existing provider code compiles without changes (errorKind defaults to UNKNOWN)

## Task Commits

Each task was committed atomically:

1. **Task 1: Add ErrorKind enum to EnrichmentResult.Error** - `4caebf3` (test) + `606105e` (feat)
2. **Task 2: Add HttpResult sealed class and fetchJsonResult()** - `cc8a0ca` (test) + `cdfe4bc` (feat)

_TDD tasks each have RED (test) and GREEN (feat) commits._

## Files Created/Modified
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentResult.kt` - Added ErrorKind enum and errorKind field on Error
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/HttpResult.kt` - New HttpResult sealed class with 5 subtypes
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/HttpClient.kt` - Added fetchJsonResult() to interface
- `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/DefaultHttpClient.kt` - Implemented fetchJsonResult() with status-aware handling
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/testutil/FakeHttpClient.kt` - Added fetchJsonResult() support with givenHttpResult and fallback
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/EnrichmentResultTest.kt` - Tests for ErrorKind enum and Error.errorKind
- `musicmeta-core/src/test/kotlin/com/landofoz/musicmeta/http/DefaultHttpClientTest.kt` - Tests for HttpResult types and FakeHttpClient.fetchJsonResult

## Decisions Made
- ErrorKind.UNKNOWN as default value ensures all 8 providers + ProviderChain compile without modification
- fetchJsonResult() has no retry logic (keeps it simple; providers handle retries at a higher level)
- JSON parse failure maps to NetworkError (not a separate type) since the body was malformed at the network/transport level
- readErrorBody reads from errorStream (not inputStream) for non-2xx responses

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
- Parallel agent (03-01) had uncommitted work-in-progress changes that broke test compilation. Waited for parallel agent to commit before running full test suite. No code changes needed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness
- Error categorization complete, ready for providers to use ErrorKind in catch blocks
- HttpResult available for providers to adopt fetchJsonResult() for precise error handling
- Phase 03 (Public API Cleanup) fully complete with both plans done

## Self-Check: PASSED

All 7 files verified present. All 4 commits verified in git log.

---
*Phase: 03-public-api-cleanup*
*Completed: 2026-03-21*
