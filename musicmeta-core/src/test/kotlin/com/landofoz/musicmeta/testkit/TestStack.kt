package com.landofoz.musicmeta.testkit

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
 * reads the client at `EnrichmentEngine.kt:179` and the keys at `:215` and `:221`, so every setter
 * has to precede it. Call [EnrichmentEngine.Builder.httpClient] after and the providers hold a real
 * `DefaultHttpClient` — the "offline" test hits the network with no error to say so. Call
 * [EnrichmentEngine.Builder.apiKeys] after and the key-gated providers are silently missing.
 *
 * **Three providers are key-gated, not four** (`EnrichmentEngine.kt:222-232`): Last.fm, fanart.tv and
 * Discogs. `ListenBrainzProvider` registers unconditionally at `:212` and takes the token as an
 * optional `authToken`, which gates one capability rather than the provider. So [ALL_KEYS] yields
 * **12** registered providers and a keyless build yields 9 — assert that off [eachProviderCapability]
 * rather than a literal, because the number is a fact about the engine, not about this file.
 *
 * **Use `runTest`, not `runBlocking`.** [EnrichmentEngine.Builder.withDefaultProviders] builds real
 * `RateLimiter`s (MusicBrainz 1100ms at `:189`, Discogs 1100ms at `:200`, iTunes 3000ms by
 * constructor default at `:211`), and `RateLimiter.execute` calls `kotlinx.coroutines.delay`. Under
 * `runTest` that delay is virtual and free; under `runBlocking` one multi-type scenario costs tens of
 * real seconds on every build.
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
    val ALL_KEYS = ApiKeyConfig(
        lastFmKey = "test",
        fanartTvProjectKey = "test",
        discogsPersonalToken = "test",
        listenBrainzToken = "test",
    )
}
