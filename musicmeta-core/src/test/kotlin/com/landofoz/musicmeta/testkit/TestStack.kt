package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.ApiKey
import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The only place that knows how to build an [EnrichmentEngine] for a composed-stack test: the real
 * default provider set, offline, fed by [http].
 *
 * **Call order is load-bearing and lives only here.** [EnrichmentEngine.Builder.withDefaultProviders]
 * reads the client and the api keys as it registers each provider, so every setter has to precede
 * it. Call [EnrichmentEngine.Builder.httpClient] after and the providers hold a real
 * `DefaultHttpClient` — the "offline" test hits the network with no error to say so. Call
 * [EnrichmentEngine.Builder.apiKeys] after and the key-gated providers are silently missing.
 *
 * **Three providers are key-gated, not four:** Last.fm, fanart.tv and Discogs.
 * `ListenBrainzProvider` registers unconditionally and takes the token as an optional `authToken`,
 * which gates one capability rather than the provider. So [ALL_KEYS] yields **12** registered
 * providers and a keyless build yields 9 — assert that off [eachProviderCapability]
 * rather than a literal, because the number is a fact about the engine, not about this file.
 *
 * **A scenario's rate-limiter waits under `enrich()` are wall-clock time, not virtual.**
 * [EnrichmentEngine.Builder.withDefaultProviders] builds real `RateLimiter`s (MusicBrainz and
 * Discogs 1100ms, iTunes 3000ms by constructor default) and `RateLimiter.execute` calls
 * `kotlinx.coroutines.delay` — but `enrich()` runs its fan-out on the engine's detached scope, so
 * `runTest`'s scheduler never sees those delays. One MusicBrainz round trip costs a scenario about
 * 1.1s of real time, and `enrichTimeoutMs` is spent against the same clock. `runTest` is still the
 * harness convention — its 60s cap turns a stalled run into a failure rather than a hung build.
 *
 * **The stack owns the dispatcher that budget is spent on.** [ownedFanOutPool] gives each built
 * stack threads no other test can occupy, so a neighbour's complete-and-cache work cannot spend a
 * scenario's budget for it and leave every unasked type stamped `Error(TIMEOUT)`. Pinned by
 * `the stack runs its fan-out off the pool the rest of the suite shares` in `TestKitTest`.
 */
internal object TestStack {

    fun build(
        http: FakeHttpClient,
        cache: EnrichmentCache = FakeEnrichmentCache(),
        keys: ApiKeyConfig = ALL_KEYS,
    ): EnrichmentEngine =
        EnrichmentEngine.Builder()
            .httpClient(http)
            .apiKeys(keys)
            .cache(cache)
            .withDefaultProviders()
            .buildOn(ownedFanOutPool())

    /**
     * The pool this stack alone runs its fan-out on. `enrich()` spends [EnrichmentConfig
     * .enrichTimeoutMs] and every rate-limiter wait beneath it against the wall clock of whatever
     * dispatcher it was handed, so on `Dispatchers.Default` a neighbouring class's
     * complete-and-cache work can still be holding the pool when a scenario starts and spend that
     * budget for it. One pool per stack, not one for the harness, so no composed test can deny
     * another. Zero core threads and a five-second keep-alive: the threads are daemons and reap
     * themselves, so a stack needs no teardown to build.
     */
    private fun ownedFanOutPool() =
        ThreadPoolExecutor(0, 2, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { runnable ->
            Thread(runnable, "test-stack-fanout").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * Every key populated, so no key-gated provider is silently absent from a composed test. The
     * values are not credentials and reach no network: [build] hands every provider [FakeHttpClient].
     */
    val ALL_KEYS = ApiKeyConfig(ApiKey.entries.associateWith { "test" })
}
