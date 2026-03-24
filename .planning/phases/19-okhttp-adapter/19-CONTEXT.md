# Phase 19: OkHttp Adapter - Context

**Gathered:** 2026-03-24
**Status:** Ready for planning

<domain>
## Phase Boundary

New `musicmeta-okhttp` Gradle module providing `OkHttpEnrichmentClient` — an implementation of the existing `HttpClient` interface using OkHttp 4.12.0. Enables Android developers to use their existing `OkHttpClient` instance (with interceptors, pinning, pooling) instead of `DefaultHttpClient` (HttpURLConnection).

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion

All implementation choices are at Claude's discretion — pure infrastructure phase.

Key constraints from research:
- OkHttp 4.12.0 (NOT 5.x — Kotlin stdlib conflict with 2.1.0)
- Do NOT set `Accept-Encoding: gzip` manually — OkHttp handles transparent decompression
- No built-in retry logic — OkHttp users add retry via interceptors (document in KDoc)
- `fetchRedirectUrl` must disable redirects: `client.newBuilder().followRedirects(false).build()`
- Map 429→RateLimited, 400-499→ClientError, 500-599→ServerError, 200-299→Ok
- Parse `Retry-After` header as seconds × 1000 for `retryAfterMs`
- All methods use `withContext(Dispatchers.IO)` for consistency
- POST methods set `Content-Type: application/json`
- Set `User-Agent` on every request (via constructor param)
- Set `Accept: application/json` header (match DefaultHttpClient)
- Read error body via `response.body?.string()` (OkHttp handles this automatically unlike HttpURLConnection)

</decisions>

<code_context>
## Existing Code Insights

### Reusable Assets
- `HttpClient` interface (10 methods) at `musicmeta-core/.../http/HttpClient.kt`
- `HttpResult` sealed class at `musicmeta-core/.../http/HttpResult.kt`
- `DefaultHttpClient` at `musicmeta-core/.../http/DefaultHttpClient.kt` — reference implementation
- `EnrichmentEngine.Builder.httpClient()` at line 78 — the wiring point

### Established Patterns
- `musicmeta-core/build.gradle.kts` as template for new JVM module: kotlin-jvm plugin, Java 17, maven-publish, version catalog deps
- Nullable methods (`fetchJson`, `fetchBody`, etc.) return `null` on any error
- HttpResult methods preserve exact error type
- `withContext(Dispatchers.IO)` wraps all blocking calls
- `org.json` for JSON parsing (JSONObject/JSONArray)

### Integration Points
- `EnrichmentEngine.Builder.httpClient(client)` — consumer passes OkHttpEnrichmentClient here
- `settings.gradle.kts` needs `include(":musicmeta-okhttp")`
- `gradle/libs.versions.toml` needs okhttp + mockwebserver entries
- Module depends on `musicmeta-core` (api) for HttpClient/HttpResult types

</code_context>

<specifics>
## Specific Ideas

No specific requirements — infrastructure phase. Follow DefaultHttpClient semantics for all 10 methods, use MockWebServer for tests (not fakes — testing real OkHttp integration).

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>
