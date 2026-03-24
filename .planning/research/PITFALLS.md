# Pitfalls Research

**Domain:** Adding OkHttp adapter, stale-while-revalidate cache, bulk enrichment API, and Maven Central publishing to an existing Kotlin/JVM music metadata library
**Researched:** 2026-03-24
**Confidence:** HIGH (codebase analysis, official docs) / MEDIUM (community-verified patterns)

---

## Critical Pitfalls

### Pitfall 1: Manually Setting Accept-Encoding Disables OkHttp Transparent Decompression

**What goes wrong:**
OkHttp automatically adds `Accept-Encoding: gzip`, negotiates compression, and transparently decompresses the response body before returning it to the caller. If you manually add `Accept-Encoding: gzip` to the request headers (to match `DefaultHttpClient.openConnection()` which explicitly sets it), OkHttp detects the manual override, disables transparent decompression, and hands you a compressed byte stream. The `org.json.JSONObject` parser then throws a `JSONException` because it receives binary gzip data instead of UTF-8 JSON.

`DefaultHttpClient` sets `Accept-Encoding: gzip` explicitly and then manually unwraps via `GZIPInputStream` in `responseStream()`. The OkHttp adapter must not replicate this pattern — the two mechanisms conflict.

**Why it happens:**
The implementation plan correctly documents this distinction, but the instinct when porting `DefaultHttpClient` behavior is to mirror every request header. The `openConnection()` helper sets four headers: `User-Agent`, `Accept`, `Accept-Encoding: gzip`, and timeouts. Three of them are correct to port; `Accept-Encoding` is the trap.

**How to avoid:**
Do NOT set `Accept-Encoding` on OkHttp requests. Let OkHttp handle it. Set only `User-Agent` and `Accept: application/json` on every request. The `docs/v0.8.0.md` plan already states this correctly ("GZIP: handled automatically by OkHttp — no manual GZIPInputStream"). Do not deviate from this.

Test with an endpoint that actually returns gzip-compressed responses (e.g., MusicBrainz — it gzip-compresses by default). The integration test using `MockWebServer` with a gzip-encoded body will catch this if written.

**Warning signs:**
- `JSONException: Value ??? of type org.json.JSONObject$1 cannot be converted to JSONObject` on what should be a valid JSON endpoint
- `response.body?.string()` contains binary garbage starting with `\u001f\u008b` (gzip magic bytes)
- `Content-Encoding: gzip` header visible in `MockWebServer` recorded request

**Phase to address:** Phase 1 (OkHttp adapter) — write a `MockWebServer` test that serves a gzip-compressed JSON response and assert the parsed `JSONObject` is correct.

---

### Pitfall 2: OkHttp fetchRedirectUrl Does Not Use followRedirects(false) Per-Call Without a New Client

**What goes wrong:**
`DefaultHttpClient.fetchRedirectUrl()` sets `instanceFollowRedirects = false` on a single `HttpURLConnection` before connecting. OkHttp has no equivalent per-call flag — `followRedirects` is a property of the `OkHttpClient` instance. To disable redirect following for only `fetchRedirectUrl()`, you must call `client.newBuilder().followRedirects(false).build()` to get a modified client for that one call.

The `docs/v0.8.0.md` plan correctly specifies this approach. The pitfall is implementing it wrong — creating a new client inside `buildGetRequest()` rather than creating it lazily once at construction time and storing it, or forgetting that `followSslRedirects` is a separate property that also needs to be set to `false` for HTTPS-to-HTTPS redirects (Cover Art Archive uses HTTPS).

**Why it happens:**
`OkHttpClient.newBuilder()` creates a shallow clone of the client, reusing its connection pool and dispatcher. It is cheap to call but the result should not be thrown away — if you create a new "no-redirect" client per-call, the connection pool won't be shared and you defeat the purpose of using OkHttp.

**How to avoid:**
Create `private val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()` once in the `OkHttpEnrichmentClient` constructor. Use it only inside `fetchRedirectUrl()`. All other methods use the original `client`. This reuses the same connection pool and dispatcher.

Test with a `MockWebServer` test that returns a 307 redirect and assert `fetchRedirectUrl()` returns the `Location` header value, not the final resolved URL. The Cover Art Archive use case requires the raw redirect URL, not the destination.

**Warning signs:**
- `fetchRedirectUrl()` follows the redirect and returns the final image URL instead of the CDN redirect URL
- Cover Art Archive images appear as 404 in consumers that expect a redirect URL to follow themselves
- Memory growth during high-volume requests (new client per call without shared connection pool)

**Phase to address:** Phase 1 (OkHttp adapter) — include a `MockWebServer` test for `fetchRedirectUrl()` that asserts the returned URL equals the `Location` header, not the destination.

---

### Pitfall 3: Stale Cache Re-Writes Expired Entries With a Fresh TTL

**What goes wrong:**
The engine's cache write loop at line 97 of `DefaultEnrichmentEngine.kt` writes all `Success` results to cache:

```kotlin
if (result is EnrichmentResult.Success) {
    val ttl = config.ttlOverrides[type] ?: type.defaultTtlMs
    cache.put(primaryKey, type, result, ttl)
}
```

After the stale fallback runs, stale results are `EnrichmentResult.Success` instances with `isStale = true`. Without an explicit guard, the write loop will re-cache them with a fresh TTL. An album cover image cached 30 days ago, served stale because the provider is down, would then be locked in cache for another 30 days — even after the provider recovers. The entry is refreshed indefinitely as long as the provider fails on each call.

The `docs/v0.8.0.md` plan correctly identifies this and specifies adding `&& !result.isStale` to the guard. The risk is forgetting it during implementation.

**Why it happens:**
The `isStale` field is added to `EnrichmentResult.Success` with a default of `false`. Existing code that checks `is EnrichmentResult.Success` handles stale results identically to fresh results. The write loop has no reason to look at `isStale` without deliberate attention.

**How to avoid:**
Change the cache write guard to:
```kotlin
if (result is EnrichmentResult.Success && !result.isStale) {
```

Write a test: `stale result is not re-written to cache` — the `docs/v0.8.0.md` plan lists this as a required test. Verify with `FakeEnrichmentCache.storedTtls` that no entry was written for a type that returned a stale result.

**Warning signs:**
- Stale result TTL in `FakeEnrichmentCache.storedTtls` matches the type's `defaultTtlMs` rather than being absent
- Provider recovers but consumers keep getting `isStale = true` results
- `FakeEnrichmentCache.stored` contains entries with `isStale = true` after an `enrich()` call with `STALE_IF_ERROR`

**Phase to address:** Phase 2 (stale cache) — the `stale result is not re-written to cache` test must be one of the first tests written for the engine changes.

---

### Pitfall 4: InMemoryEnrichmentCache Mutex Deadlock in getIncludingExpired

**What goes wrong:**
`InMemoryEnrichmentCache.get()` acquires `mutex.withLock { ... }`. When `getIncludingExpired()` is added, it must also acquire the same mutex. If either method calls the other (e.g., `getIncludingExpired` calling `get()` internally to reduce code duplication), the `Mutex` in `kotlinx.coroutines.sync` is **not reentrant** — the second `withLock` call on the same coroutine will deadlock.

**Why it happens:**
`java.util.concurrent.locks.ReentrantLock` is reentrant. Kotlin's `Mutex` is not. Developers familiar with Java locking expect lock re-entry to work.

**How to avoid:**
Both `get()` and `getIncludingExpired()` must each directly access `entries` map without calling each other. Extract the shared read logic into a `private fun readEntry(key: String): EnrichmentResult.Success?` that is called from within an already-held lock (not itself using `mutex.withLock`). The current `get()` method is short enough (4 lines of logic) that duplication is acceptable.

The mutex must be acquired in both public methods:
```kotlin
override suspend fun getIncludingExpired(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? = mutex.withLock {
    entries[cacheKey(entityKey, type)]?.result  // no expiry check
}
```

**Warning signs:**
- Test hangs indefinitely when exercising stale cache path
- Coroutine test times out in `InMemoryEnrichmentCacheTest`
- Deadlock only reproducible when running the full test suite (Dispatchers.IO context)

**Phase to address:** Phase 2 (stale cache) — write `getIncludingExpired()` in `InMemoryEnrichmentCache` with mutex correctness as the primary concern.

---

### Pitfall 5: OSSRH (oss.sonatype.org) Is EOL — The docs/v0.8.0.md Plan Points at a Dead System

**What goes wrong:**
The `docs/v0.8.0.md` plan says to add "OSSRH repository URLs (snapshots + releases)" and credentials from `gradle.properties`. Sonatype OSSRH (`oss.sonatype.org` and `s01.oss.sonatype.org`) **reached end-of-life on June 30, 2025** and is shut down. Publishing to those endpoints will fail with connection errors or 403s. The plan's publishing approach targets a dead system.

The replacement is **Sonatype Central Portal** (`central.sonatype.com`), which uses a completely different API and authentication model.

**Why it happens:**
The OSSRH/`maven-publish` + `signing` Gradle pattern was the standard for years. Most blog posts, StackOverflow answers, and tutorials still describe the OSSRH flow. The `docs/v0.8.0.md` plan was written from that established pattern without checking current state.

**How to avoid:**
Use `com.vanniktech.maven.publish` plugin (current version 0.36.0+) instead of raw `maven-publish` + `signing` in a `subprojects` block. It supports Central Portal natively:

```kotlin
// In each module's build.gradle.kts
plugins {
    id("com.vanniktech.maven.publish") version "0.36.0"
}

mavenPublishing {
    publishToMavenCentral()  // targets Central Portal, not OSSRH
    signAllPublications()
}
```

Credentials for Central Portal are **user tokens** generated at `central.sonatype.com/account`, not your login password. Set `mavenCentralUsername` and `mavenCentralPassword` in `~/.gradle/gradle.properties` or as `ORG_GRADLE_PROJECT_mavenCentralUsername` environment variables.

The `docs/v0.8.0.md` plan's "subprojects block with POM metadata + OSSRH URLs" approach requires rewriting to use the vanniktech plugin per-module. The signing configuration is the same (GPG key), but the repository configuration is different.

**Warning signs:**
- `401 Unauthorized` or `Connection refused` when publishing to `oss.sonatype.org`
- `publishMavenPublicationToSonatypeRepository` task fails immediately
- Gradle docs or community posts referencing `https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/`

**Phase to address:** Phase 4 (Maven Central publishing) — replace the entire OSSRH approach with the vanniktech plugin targeting Central Portal before writing any publishing config.

---

### Pitfall 6: Signing Plugin subprojects Block Configuration Timing Errors

**What goes wrong:**
The `docs/v0.8.0.md` plan places signing configuration in a root `subprojects {}` block. Configuring the `signing` plugin and referencing `publications` inside `subprojects {}` triggers configuration-time resolution of tasks that don't exist yet. For the `musicmeta-android` module, the `release` publication component is created inside `afterEvaluate {}` (because Android library variants are not available at configuration time). The signing plugin trying to sign a publication that doesn't exist yet causes:

```
Could not get unknown property 'release' for PublicationContainer
```

or silently produces unsigned artifacts (signing task created but not wired to the publication).

**Why it happens:**
`musicmeta-core` and `musicmeta-okhttp` are pure JVM modules — their publications are available at configuration time. `musicmeta-android` requires `afterEvaluate`. A root `subprojects` block that applies uniformly to all three modules hits the Android timing issue.

**How to avoid:**
Per-module configuration is safer than a root `subprojects` block for signing. Apply the vanniktech plugin per-module (see Pitfall 5) — it handles the `afterEvaluate` timing for Android automatically.

If using raw `maven-publish` + `signing`, the Android module needs:
```kotlin
afterEvaluate {
    signing {
        sign(publishing.publications["release"])
    }
}
```

The JVM modules can configure signing at normal evaluation time. Do not use a single root `subprojects` block for signing.

**Warning signs:**
- `./gradlew :musicmeta-android:signReleasePublication` succeeds but produces no `.asc` files
- `./gradlew :musicmeta-android:publishToMavenLocal` completes without signing artifacts
- `Could not resolve com.landofoz:musicmeta-android:0.8.0` — missing `.asc` in local Maven repo

**Phase to address:** Phase 4 (Maven Central publishing) — verify `~/.m2/repository/com/landofoz/musicmeta-android/0.8.0/` contains `.asc` files after `publishToMavenLocal`.

---

### Pitfall 7: OkHttp Response Body Must Be Closed Explicitly to Avoid Connection Leaks

**What goes wrong:**
OkHttp's `Response.body` is a one-read stream that must be closed after use. If the response body is read via `body?.string()` inside a `try` block but an exception occurs between creating the response and reading the body (or the body is never read for error responses), the underlying TCP connection is leaked. Over time, the connection pool fills with leaked connections and new requests hang or fail.

`DefaultHttpClient` uses `conn.disconnect()` in a `finally` block which always releases resources. There is no equivalent automatic cleanup in OkHttp — the response body is your responsibility.

**Why it happens:**
`body?.string()` looks safe (the `?.` handles null) but it does not close the response body on the happy path — `string()` reads and closes, but only if called. For 4xx and 5xx responses where you want the error body (`response.body?.string()` for `ClientError`/`ServerError`), calling `string()` both reads and closes. The trap is success responses where you build an `HttpResult.Ok` and forget to ensure the body is always closed even if `JSONObject` parsing throws.

**How to avoid:**
Use `response.use { r -> r.body?.string() }` or `response.body?.use { it.string() }` patterns. In the `OkHttpEnrichmentClient`, the response must always be closed in a `finally` or via `use`:

```kotlin
client.newCall(request).execute().use { response ->
    val code = response.code
    when {
        code == 429 -> { /* ... */ }
        code in 400..499 -> HttpResult.ClientError(code, response.body?.string())
        code in 200..299 -> {
            val text = response.body?.string() ?: return@use HttpResult.NetworkError("empty body")
            // parse JSON...
        }
        // ...
    }
}
```

The `use` block on `Response` calls `close()` after the lambda completes regardless of exceptions.

**Warning signs:**
- `WARNING: A connection to [url] was leaked. Did you forget to close a response body?` in OkHttp logs
- `MockWebServer` test intermittently fails with "server failed to receive request" after many test iterations
- Connection pool exhaustion after running `enrichBatch()` with a large list

**Phase to address:** Phase 1 (OkHttp adapter) — every method in `OkHttpEnrichmentClient` must use `response.use { }` or `response.body?.use { }`.

---

### Pitfall 8: enrichBatch Flow Cancellation Requires Cooperative Suspension in the Loop

**What goes wrong:**
The `enrichBatch` default implementation in `EnrichmentEngine.kt` is:
```kotlin
flow {
    for (request in requests) {
        emit(request to enrich(request, types, forceRefresh))
    }
}
```

`enrich()` is a suspend function, so each iteration is a suspension point. Cancellation is cooperative — the flow will stop after the current `enrich()` call completes but before the next `emit()`. This is correct behavior for most cases. The pitfall is in the test: `enrichBatch cancellation stops processing (take(1))` must verify that not all requests were processed, but the test must account for the fact that the in-flight `enrich()` for item 1 will complete before cancellation propagates, and at minimum 1 result will always be emitted.

A separate pitfall: if `enrich()` throws an uncaught exception (not wraps it in `EnrichmentResult.Error`), the flow terminates and remaining requests are never processed. The default implementation has no exception handling around the `enrich()` call.

**Why it happens:**
The `enrich()` interface contract guarantees it returns `EnrichmentResults` and never throws (exceptions are wrapped in `EnrichmentResult.Error`). But the default `enrichBatch` in the interface calls `enrich()` without a `try/catch`. If a future `enrich()` implementation breaks this contract, or if `DefaultEnrichmentEngine` has a bug in the `STALE_IF_ERROR` path that escapes the catch block, the entire batch fails.

**How to avoid:**
Add a `try/catch` in the `DefaultEnrichmentEngine.enrichBatch()` override around the `enrich()` call that converts any escaped exception to an `EnrichmentResults` with all types as `EnrichmentResult.Error`. This makes `enrichBatch` resilient even if `enrich()` has a latent uncaught exception path.

For the cancellation test: use Turbine's `cancel()` after `take(1)` and assert that `FakeProvider.callCount` is at most 2 (one for the completed request, potentially one in-flight) and less than `requests.size`.

**Warning signs:**
- `enrichBatch` test with `take(1).collect {}` hangs if `enrich()` throws instead of wrapping errors
- Cancellation test passes when it should fail (the loop is not checking for cancellation)
- Empty request list test emits a spurious element

**Phase to address:** Phase 3 (bulk enrichment) — the cancellation test and exception-safety of the loop must both be verified.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|----------|-------------------|----------------|-----------------|
| Put signing config in root `subprojects {}` for all modules | Single place to configure | Android timing errors, unsigned artifacts silently; hard to debug | Never — per-module config is required |
| Use OSSRH URLs from old tutorials | Familiar setup | Fails immediately — OSSRH is EOL as of June 2025 | Never |
| Skip `response.use {}` and call `body?.string()` directly | Less boilerplate | Connection pool leaks in production under load | Never |
| Manually set `Accept-Encoding: gzip` in OkHttp request | Mirrors DefaultHttpClient behavior | OkHttp disables transparent decompression; binary garbage returned | Never |
| Add `isStale` guard to cache write loop as a TODO comment | Feature appears to work | Stale data locked in cache indefinitely once provider recovers | Never |
| One `OkHttpClient` instance per `OkHttpEnrichmentClient` method | Simpler code | Each call creates a new connection pool; no connection reuse | Never |
| Serve stale for `NotFound` results in STALE_IF_ERROR mode | More permissive fallback | Returns cached "no results" even if the data might exist now; misleads callers | Never |

---

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|-------------|----------------|------------------|
| OkHttp + gzip | Setting `Accept-Encoding: gzip` manually | Omit the header; OkHttp adds it and decompresses transparently |
| OkHttp redirect | Using the same `OkHttpClient` for `fetchRedirectUrl` | Create `noRedirectClient` at construction time via `newBuilder().followRedirects(false).followSslRedirects(false).build()` |
| OkHttp response body | Reading `body?.string()` without closing | Wrap with `response.use { }` or `body.use { it.string() }` on every path |
| MockWebServer | Asserting `body?.string()` in test without closing response | Use `response.use { }` in test code too — leaks cause test suite flakiness |
| Sonatype Central Portal | Using OSSRH credentials (username/password) | Generate user tokens from `central.sonatype.com/account` — login credentials rejected |
| Sonatype Central Portal | Publishing with `maven-publish` directly | Use `com.vanniktech.maven.publish` plugin — it handles Central Portal API, checksums, and upload format |
| Gradle signing | Using `signing.keyId` file-based approach in CI | Prefer `ORG_GRADLE_PROJECT_signingInMemoryKey` env var for CI — no keyring file to manage |
| Maven Central | Publishing `pom` packaging without sources/javadoc jars | Maven Central requires sources.jar and javadoc.jar for every non-pom artifact; build fails validation |
| Room cache getIncludingExpired | Adding new DAO method as `@Transaction` | Not needed — it's a read-only SELECT; `@Transaction` adds unnecessary overhead |
| EnrichmentResult.Success isStale | Using `.copy()` with all fields | `result.copy(isStale = true)` is correct; only the isStale field changes |

---

## Performance Traps

| Trap | Symptoms | Prevention | When It Breaks |
|------|----------|------------|----------------|
| `enrichBatch` with large list, all cache misses | Wallclock time = sum of all `enrich()` calls × providers | Batch is sequential by design; warn callers that cache warm-up matters | Lists larger than ~50 requests with cold cache |
| New `OkHttpClient` per request (not using shared client) | Connection pool exhausted; slow TLS handshakes for every request | Pass a shared `OkHttpClient` into `OkHttpEnrichmentClient` constructor | Any sustained usage — even 10 requests/min |
| `getIncludingExpired` on Room with no index on `expires_at` | Slow query on large cache tables | The query uses `entity_key + enrichment_type` which is the existing composite key; no performance regression | Large Room databases (10k+ entries) |
| `STALE_IF_ERROR` calling `getIncludingExpired` for every type in results | N extra DB calls per `enrich()` when provider fails | Only call `getIncludingExpired` when `cacheMode == STALE_IF_ERROR` AND result is `Error`/`RateLimited` | Always present if STALE_IF_ERROR is enabled and providers are degraded |
| OkHttp connection pool with too-short idle timeout | Connections recycled before reuse; performance regression vs DefaultHttpClient | Use default `OkHttpClient()` — it has sensible defaults (5 connections, 5min idle) | Custom `OkHttpClient` built with `connectionPool(ConnectionPool(0, ...))` |

---

## Security Mistakes

| Mistake | Risk | Prevention |
|---------|------|------------|
| Committing GPG private key to `gradle.properties` | Signing key compromise; stolen identity | Store in `~/.gradle/gradle.properties` (outside repo) or CI environment variables only |
| Hardcoding Sonatype user token in `build.gradle.kts` | Token exposure in repo history | Use `findProperty("mavenCentralUsername")` with fallback from env var |
| Publishing SNAPSHOT versions to Maven Central | SNAPSHOT artifacts blocked by Central Portal validation | Use `-SNAPSHOT` suffix only for local testing; Central Portal requires release versions |
| Logging `EnrichmentConfig` including API keys in enrichBatch | API key exposure in application logs | Do not add config logging to the batch path; keys are in `ApiKeyConfig`, not `EnrichmentConfig`, but verify |

---

## "Looks Done But Isn't" Checklist

- [ ] **OkHttp gzip:** `OkHttpEnrichmentClient` returns correct JSON for a gzip-compressed response — verify with a `MockWebServer` test serving a gzip body
- [ ] **OkHttp redirect:** `fetchRedirectUrl()` returns the `Location` header value, not the final resolved URL — verify with a `MockWebServer` 307 response
- [ ] **OkHttp body leak:** All 12 methods close the response body — verify by running `OkHttpEnrichmentClientTest` and checking OkHttp logs for "connection leaked" warnings
- [ ] **Stale guard:** Stale results are not re-written to cache — verify `FakeEnrichmentCache.stored` does not contain `isStale = true` entries after `enrich()` with `STALE_IF_ERROR`
- [ ] **Stale not-for-NotFound:** `STALE_IF_ERROR` does not serve stale for `NotFound` results — verify test `STALE_IF_ERROR does not serve stale for genuine NotFound`
- [ ] **Maven Central publishing:** All 3 modules publish with sources jar, javadoc jar, and `.asc` signatures — verify `ls ~/.m2/repository/com/landofoz/musicmeta-core/0.8.0/` shows all 6 file types (.jar, -sources.jar, -javadoc.jar, .pom, .module, and corresponding .asc)
- [ ] **Maven Central POM:** Published POM contains `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>` — verify with `cat ~/.m2/repository/com/landofoz/musicmeta-core/0.8.0/musicmeta-core-0.8.0.pom`
- [ ] **enrichBatch cancellation:** Cancelling after `take(1)` stops the loop — verify `FakeProvider.callCount` is less than `requests.size`
- [ ] **enrichBatch empty list:** Empty list emits nothing and completes normally — Turbine `awaitComplete()` without `awaitItem()`
- [ ] **Version bump:** All 3 modules publish as `0.8.0`, not `0.1.0` — verify POM `<version>` field
- [ ] **Mutex in getIncludingExpired:** `InMemoryEnrichmentCache.getIncludingExpired()` acquires the mutex — verify concurrent test does not return inconsistent results

---

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---------|---------------|----------------|
| Accept-Encoding set on OkHttp (Pitfall 1) | LOW | Remove the header from `buildGetRequest()`; existing `MockWebServer` tests will catch it |
| fetchRedirectUrl follows redirects (Pitfall 2) | LOW | Create `noRedirectClient` in constructor; update `fetchRedirectUrl()` to use it |
| Stale results cached with fresh TTL (Pitfall 3) | LOW | Add `&& !result.isStale` to cache write guard at engine line 97 |
| Mutex deadlock in getIncludingExpired (Pitfall 4) | LOW | Rewrite to use direct `entries[]` access under existing lock, not calling `get()` |
| OSSRH EOL, publishing fails (Pitfall 5) | MEDIUM | Replace raw `maven-publish` config with vanniktech plugin; reconfigure credentials for Central Portal |
| Unsigned Android artifacts (Pitfall 6) | LOW | Move signing config into `afterEvaluate {}` per-module; verify `.asc` files in local Maven repo |
| Response body leak (Pitfall 7) | LOW | Wrap all `execute()` calls with `response.use { }` |
| enrichBatch exception propagation (Pitfall 8) | LOW | Add `try/catch` around `enrich()` in `DefaultEnrichmentEngine.enrichBatch()` override |

---

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---------|------------------|--------------|
| OkHttp gzip decompression (Pitfall 1) | Phase 1 | MockWebServer test: gzip-encoded JSON body returns correct `JSONObject` |
| fetchRedirectUrl follows redirects (Pitfall 2) | Phase 1 | MockWebServer test: 307 response returns `Location` header value, not resolved URL |
| Stale results re-cached (Pitfall 3) | Phase 2 | Unit test: `stale result is not re-written to cache` — FakeEnrichmentCache has no stale entries |
| Mutex deadlock (Pitfall 4) | Phase 2 | Unit test: concurrent `get()` + `getIncludingExpired()` calls complete without hanging |
| OSSRH EOL (Pitfall 5) | Phase 4 | `./gradlew publishToMavenLocal` succeeds; artifacts visible in `~/.m2/repository/com/landofoz/` |
| Android signing timing (Pitfall 6) | Phase 4 | `ls ~/.m2/.../musicmeta-android/0.8.0/` includes `.asc` files |
| Response body leak (Pitfall 7) | Phase 1 | OkHttp logs show no "connection leaked" warnings after full test suite |
| enrichBatch exception path (Pitfall 8) | Phase 3 | Test: `enrich()` throwing exception does not terminate the batch flow |

---

## docs/v0.8.0.md Plan Flags

Specific issues in the current implementation plan that need correction:

| Location in Plan | Issue | Correction |
|------------------|-------|------------|
| Phase 4, "OSSRH repository URLs (snapshots + releases)" | OSSRH is EOL as of June 30, 2025 | Replace with Central Portal via vanniktech plugin |
| Phase 4, "subprojects block with signing plugin" | Causes Android `afterEvaluate` timing errors | Use per-module vanniktech plugin config |
| Phase 4, "Credentials from gradle.properties" | Central Portal uses user tokens, not login credentials | Token generation from central.sonatype.com, not oss.sonatype.org |
| Phase 1, "Constructor: (client: OkHttpClient, userAgent: String)" | Correct. Do not add Accept-Encoding header. | No change needed, but explicitly confirmed |
| Phase 2, "Cache write loop line 97: ADD: && !result.isStale" | Correct identification, needs explicit implementation | Verify this guard is in the implementation checklist |
| Phase 2, "getIncludingExpired must also use mutex.withLock" | Correct. Do not call get() from getIncludingExpired(). | Implement as direct map access under lock |

---

## Sources

- Codebase analysis: `DefaultHttpClient.kt` (289 lines), `InMemoryEnrichmentCache.kt`, `DefaultEnrichmentEngine.kt`, `RoomEnrichmentCache.kt`, `EnrichmentResult.kt`, `EnrichmentCache.kt`, `HttpClient.kt`, `EnrichmentEngine.kt`, `build.gradle.kts` (all modules), `gradle/libs.versions.toml`
- Implementation plan reviewed: `docs/v0.8.0.md`
- OkHttp transparent gzip: [OkHttp issue #1579](https://github.com/square/okhttp/issues/1579) — confirms manual `Accept-Encoding` disables transparent decompression
- OkHttp response body close: [okhttp-coroutines README](https://github.com/square/okhttp/blob/master/okhttp-coroutines/README.md) — `response.use {}` pattern
- OSSRH EOL: [Gradle Forums thread](https://discuss.gradle.org/t/publishing-to-maven-central-in-2025-ossrh-eol/50983) — confirmed shutdown June 30, 2025
- OSSRH shutdown tracking: [GitHub issue #11512 IQSS/dataverse](https://github.com/IQSS/dataverse/issues/11512)
- vanniktech plugin Central Portal: [vanniktech.github.io](https://vanniktech.github.io/gradle-maven-publish-plugin/central/) — current setup for Central Portal
- Maven Central requirements: [central.sonatype.org/publish/requirements](https://central.sonatype.org/publish/requirements/) — required POM fields, sources/javadoc jars, GPG signing
- Gradle signing with subprojects: [gradle/gradle issue #13419](https://github.com/gradle/gradle/issues/13419) — afterEvaluate timing requirement for Android
- Kotlin coroutines Flow cancellation: [Kotlin docs](https://kotlinlang.org/docs/flow.html) — cooperative cancellation at suspension points
- Kotlin data class default parameter binary compat: [Kotlin binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator) — `isStale: Boolean = false` is source-compatible but not binary-compatible (acceptable pre-1.0)

---
*Pitfalls research for: v0.8.0 Production Readiness — OkHttp adapter, stale cache, bulk enrichment, Maven Central publishing*
*Researched: 2026-03-24*
