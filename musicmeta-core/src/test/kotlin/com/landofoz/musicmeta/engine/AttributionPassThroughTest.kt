package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.contract.AttributionStates
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution is descriptive payload data and the engine never acts on it. A Wikipedia answer whose
 * attribution is missing, partial, restrictive, contradictory or carrying unsafe links is returned
 * as `Success` with its text or URL and its attribution unchanged, by every route a result takes to
 * a caller, and the write-back stores it. None of these states may become a `NotFound`, an `Error`,
 * a refetch or a promotion of another provider.
 */
class AttributionPassThroughTest {

    private val request = EnrichmentRequest.forArtist("Radiohead")

    @Test
    fun `an answer with no attribution is served unchanged by every route`() = runTest {
        // Given - a Wikipedia answer that carries no attribution
        val attribution = AttributionStates.missing

        // When - it is served by live fan-out, a cache hit, a stale substitute, a selected entry, a custom cache and the merger
        val outcomes = serveByEveryRoute(attribution)

        // Then - every route returns the same content and the stored copy is unchanged too
        assertEveryRouteUnchanged(outcomes)
    }

    @Test
    fun `an answer with partial attribution and no source URL is served unchanged by every route`() = runTest {
        // Given - a Wikipedia answer whose attribution has a creator and licence but no source URL
        val attribution = AttributionStates.partial

        // When - it is served by every route
        val outcomes = serveByEveryRoute(attribution)

        // Then - every route returns the same content and the stored copy is unchanged too
        assertEveryRouteUnchanged(outcomes)
    }

    @Test
    fun `an answer with restrictive attribution is served unchanged by every route`() = runTest {
        // Given - a Wikipedia answer whose attribution lists restrictions and a non-free licence
        val attribution = AttributionStates.restrictive

        // When - it is served by every route
        val outcomes = serveByEveryRoute(attribution)

        // Then - every route returns the same content and the stored copy is unchanged too
        assertEveryRouteUnchanged(outcomes)
    }

    @Test
    fun `an answer with contradictory attribution is served unchanged by every route`() = runTest {
        // Given - a Wikipedia answer whose licence and copyright flags disagree with each other
        val attribution = AttributionStates.contradictory

        // When - it is served by every route
        val outcomes = serveByEveryRoute(attribution)

        // Then - every route returns the same content and the stored copy is unchanged too
        assertEveryRouteUnchanged(outcomes)
    }

    @Test
    fun `an answer with unsafe attribution links is served unchanged by every route`() = runTest {
        // Given - a Wikipedia answer whose links use a script scheme, plain http and control characters
        val attribution = AttributionStates.unsafeLink

        // When - it is served by every route
        val outcomes = serveByEveryRoute(attribution)

        // Then - every route returns the same content and the stored copy is unchanged too
        assertEveryRouteUnchanged(outcomes)
    }

    @Test
    fun `a merged photo keeps the winning provider and its attribution when another provider disagrees`() = runTest {
        // Given - Wikipedia and a second provider both answering ARTIST_PHOTO, Wikipedia the more confident
        val wikipediaPhoto = photo(AttributionStates.restrictive)
        val other = FakeProvider(id = "deezer", capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_PHOTO, 50)))
            .also { it.givenResult(EnrichmentType.ARTIST_PHOTO, success(EnrichmentType.ARTIST_PHOTO, photo(null, "https://cdn.example.test/deezer.jpg"), "deezer", 0.8f)) }
        val wikipedia = wikipediaProvider(photo = wikipediaPhoto)

        // When - the engine merges the two with the artwork merger
        val engine = engine(listOf(wikipedia, other), FakeEnrichmentCache(), mergers = listOf(ArtworkMerger(EnrichmentType.ARTIST_PHOTO)))
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_PHOTO)).raw[EnrichmentType.ARTIST_PHOTO]

        // Then - Wikipedia still wins, its URL and attribution are unchanged, and the other provider is an alternative
        val success = result as EnrichmentResult.Success
        val merged = success.data as EnrichmentData.Artwork
        assertEquals("wikipedia", success.provider)
        assertEquals(wikipediaPhoto.url, merged.url)
        assertEquals(wikipediaPhoto.attribution, merged.attribution)
        assertEquals(listOf("deezer"), merged.alternatives?.map(ArtworkSource::provider))
    }

    private data class Outcome(
        val route: String,
        val expected: EnrichmentData,
        val result: EnrichmentResult?,
        /** What the cache received from the engine's write-back, or null for a route that must not write. */
        val written: EnrichmentData?,
        val mustWrite: Boolean,
    )

    private fun assertEveryRouteUnchanged(outcomes: List<Outcome>) {
        for (outcome in outcomes) {
            val result = outcome.result
            assertTrue("${outcome.route}: expected Success, got $result", result is EnrichmentResult.Success)
            assertEquals("${outcome.route}: served payload", outcome.expected, (result as EnrichmentResult.Success).data)
            if (outcome.mustWrite) {
                assertEquals("${outcome.route}: stored payload", outcome.expected, outcome.written)
            }
        }
    }

    private suspend fun serveByEveryRoute(attribution: Attribution?): List<Outcome> {
        val bio = biography(attribution)
        val photo = photo(attribution)
        return listOf(
            liveFanOut(bio),
            freshCacheHit(bio),
            staleSubstitute(bio),
            manuallySelectedEntry(bio),
            customDelegatingCache(bio),
            mergedPhoto(photo),
        )
    }

    private suspend fun liveFanOut(bio: EnrichmentData.Biography): Outcome {
        val cache = FakeEnrichmentCache()
        val provider = wikipediaProvider(bio = bio)
        val result = engine(listOf(provider), cache).enrich(request, setOf(BIO)).raw[BIO]
        return Outcome("live fan-out", bio, result, cache.stored[cacheKey(BIO)]?.data, mustWrite = true)
    }

    private suspend fun freshCacheHit(bio: EnrichmentData.Biography): Outcome {
        val cache = FakeEnrichmentCache()
        cache.stored[cacheKey(BIO)] = success(BIO, bio)
        val provider = wikipediaProvider(bio = null)
        val result = engine(listOf(provider), cache).enrich(request, setOf(BIO)).raw[BIO]
        check(provider.enrichCalls.isEmpty()) { "a fresh hit must not reach the provider" }
        return Outcome("fresh cache hit", bio, result, null, mustWrite = false)
    }

    private suspend fun staleSubstitute(bio: EnrichmentData.Biography): Outcome {
        val cache = FakeEnrichmentCache()
        cache.expiredStore[cacheKey(BIO)] = success(BIO, bio)
        val provider = wikipediaProvider(bio = null).also {
            it.givenResult(BIO, EnrichmentResult.Error(BIO, "wikipedia", "upstream down"))
        }
        val result = engine(listOf(provider), cache, CacheMode.STALE_IF_ERROR).enrich(request, setOf(BIO)).raw[BIO]
        check((result as? EnrichmentResult.Success)?.isStale != false) { "expected a stale substitute, got $result" }
        return Outcome("stale substitution", bio, result, null, mustWrite = false)
    }

    private suspend fun manuallySelectedEntry(bio: EnrichmentData.Biography): Outcome {
        val cache = FakeEnrichmentCache()
        cache.stored[cacheKey(BIO)] = success(BIO, bio)
        val provider = wikipediaProvider(bio = null)
        val engine = engine(listOf(provider), cache)
        engine.markManuallySelected(request, BIO)
        check(engine.isManuallySelected(request, BIO)) { "the entry must be marked selected" }
        val result = engine.enrich(request, setOf(BIO)).raw[BIO]
        return Outcome("manually selected entry", bio, result, null, mustWrite = false)
    }

    private suspend fun customDelegatingCache(bio: EnrichmentData.Biography): Outcome {
        val cache = RecordingCache(FakeEnrichmentCache())
        val provider = wikipediaProvider(bio = bio)
        val engine = engine(listOf(provider), cache)
        engine.enrich(request, setOf(BIO))
        val second = engine.enrich(request, setOf(BIO)).raw[BIO]
        check(provider.enrichCalls.size == 1) { "the second call must be served by the custom cache" }
        return Outcome("custom delegating cache", bio, second, cache.puts.singleOrNull()?.data, mustWrite = true)
    }

    private suspend fun mergedPhoto(photo: EnrichmentData.Artwork): Outcome {
        val cache = FakeEnrichmentCache()
        val provider = wikipediaProvider(photo = photo)
        val result = engine(listOf(provider), cache, mergers = listOf(ArtworkMerger(PHOTO)))
            .enrich(request, setOf(PHOTO)).raw[PHOTO]
        return Outcome("ARTIST_PHOTO through the merger", photo, result, cache.stored[cacheKey(PHOTO)]?.data, mustWrite = true)
    }

    private fun biography(attribution: Attribution?) = EnrichmentData.Biography(
        text = "Radiohead are an English rock band formed in Abingdon.",
        source = "Wikipedia",
        thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/330px-Radiohead.jpg",
        attribution = attribution,
    )

    private fun photo(attribution: Attribution?, url: String = "https://upload.wikimedia.org/wikipedia/commons/a/a1/Radiohead.jpg") =
        EnrichmentData.Artwork(url = url, width = 330, attribution = attribution)

    private fun success(type: EnrichmentType, data: EnrichmentData, provider: String = "wikipedia", confidence: Float = 0.95f) =
        EnrichmentResult.Success(type = type, data = data, provider = provider, confidence = confidence)

    /** A fake provider named `wikipedia` serving whichever of [bio] and [photo] it is given. */
    private fun wikipediaProvider(bio: EnrichmentData.Biography? = null, photo: EnrichmentData.Artwork? = null) =
        FakeProvider(
            id = "wikipedia",
            capabilities = listOf(ProviderCapability(BIO, 100), ProviderCapability(PHOTO, 30)),
        ).also { provider ->
            bio?.let { provider.givenResult(BIO, success(BIO, it)) }
            photo?.let { provider.givenResult(PHOTO, success(PHOTO, it)) }
        }

    private fun engine(
        providers: List<FakeProvider>,
        cache: EnrichmentCache,
        cacheMode: CacheMode = CacheMode.NETWORK_FIRST,
        mergers: List<ResultMerger> = emptyList(),
    ) = DefaultEnrichmentEngine(
        ProviderRegistry(providers),
        cache,
        EnrichmentConfig(enableIdentityResolution = false, cacheMode = cacheMode),
        mergers = mergers,
    )

    private fun cacheKey(type: EnrichmentType) = "${DefaultEnrichmentEngine.entityKeyFor(request, type)}:$type"

    /** A consumer's own cache that forwards everything to another and records what it was asked to store. */
    private class RecordingCache(private val inner: FakeEnrichmentCache) : EnrichmentCache by inner {
        val puts = mutableListOf<EnrichmentResult.Success>()

        override suspend fun put(
            entityKey: String,
            type: EnrichmentType,
            result: EnrichmentResult.Success,
            canonicalStatus: CanonicalStatus,
            ttlMs: Long,
        ) {
            puts += result
            inner.put(entityKey, type, result, canonicalStatus, ttlMs)
        }
    }

    private companion object {
        val BIO = EnrichmentType.ARTIST_BIO
        val PHOTO = EnrichmentType.ARTIST_PHOTO
    }
}
