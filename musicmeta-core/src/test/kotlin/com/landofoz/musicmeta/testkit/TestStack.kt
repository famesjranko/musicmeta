package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.ApiKey
import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient

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
 * `kotlinx.coroutines.delay` — but `enrich()` runs its fan-out on the engine's detached scope, on
 * `Dispatchers.Default`, so `runTest`'s scheduler never sees those delays. One MusicBrainz round
 * trip costs a scenario about 1.1s of real time, and `enrichTimeoutMs` is spent against the same
 * clock: a fan-out denied that shared pool spends the budget waiting and stamps `Error(TIMEOUT)`
 * on types it never got to ask about. `runTest` is still the harness convention — its 60s cap
 * turns such a run into a failure rather than a hung build.
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
            .build()

    /**
     * Every key populated, so no key-gated provider is silently absent from a composed test. The
     * values are not credentials and reach no network: [build] hands every provider [FakeHttpClient].
     */
    val ALL_KEYS = ApiKeyConfig(ApiKey.entries.associateWith { "test" })
}
