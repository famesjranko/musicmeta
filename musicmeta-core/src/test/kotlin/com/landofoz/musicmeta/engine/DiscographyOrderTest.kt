package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzMapper
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzTopReleaseGroup
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzMapper
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzParser
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzReleaseGroup
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ARTIST_DISCOGRAPHY` reaches a caller in the order `EnrichmentData.Discography` documents —
 * ascending year, undated albums last, the provider's own order kept within a year and across the
 * undated block — whoever produced it and whichever route it came out on.
 *
 * The payloads come from two captured upstream responses rather than from hand-built albums, since
 * what is being pinned is a claim about real data: that MusicBrainz sends undated release groups in
 * an order that is not chronological, and that ListenBrainz sends none that are dated at all.
 */
class DiscographyOrderTest {

    private val request = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(musicBrainzId = "artist-mbid"),
        name = "Tenacious D",
    )

    /** The captured MusicBrainz browse, mapped exactly as `MusicBrainzArtistEnrichment` maps it. */
    private fun musicBrainzDiscography(): EnrichmentData.Discography {
        val body = UpstreamPools.body(MB_POOL, "musicbrainz-release-group-browse.json")
        val groups: List<MusicBrainzReleaseGroup> = MusicBrainzParser.parseReleaseGroups(JSONObject(body))
        return MusicBrainzMapper.toDiscography(groups)
    }

    /** The captured ListenBrainz popularity response, mapped exactly as `ListenBrainzProvider` maps it. */
    private fun listenBrainzDiscography(): EnrichmentData.Discography {
        val body = UpstreamPools.body(LB_POOL, "listenbrainz-top-release-groups.json")
        val items = JSONArray(body)
        val groups = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            ListenBrainzTopReleaseGroup(
                releaseGroupMbid = item.getString("release_group_mbid"),
                releaseGroupName = item.getJSONObject("release_group").getString("name"),
                artistName = item.getJSONObject("artist").getJSONArray("artists").getJSONObject(0).getString("name"),
                listenCount = item.getLong("total_listen_count"),
            )
        }
        return ListenBrainzMapper.toDiscography(groups)
    }

    private fun provider(data: EnrichmentData.Discography) = FakeProvider(
        id = "discography",
        capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_DISCOGRAPHY, 100)),
    ).also {
        it.givenResult(
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentResult.Success(EnrichmentType.ARTIST_DISCOGRAPHY, data, "discography", 1f),
        )
    }

    private fun engine(cache: InMemoryEnrichmentCache, vararg providers: FakeProvider) =
        DefaultEnrichmentEngine(
            ProviderRegistry(providers.toList()),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        )

    private suspend fun cacheHolding(data: EnrichmentData.Discography): InMemoryEnrichmentCache {
        val cache = InMemoryEnrichmentCache()
        cache.put(
            DefaultEnrichmentEngine.entityKeyFor(request, EnrichmentType.ARTIST_DISCOGRAPHY),
            EnrichmentType.ARTIST_DISCOGRAPHY,
            EnrichmentResult.Success(EnrichmentType.ARTIST_DISCOGRAPHY, data, "cached-provider", 1f),
            CanonicalStatus.RESOLVED,
            60_000,
        )
        return cache
    }

    private suspend fun albumsOf(engine: DefaultEnrichmentEngine): List<Pair<String, Int?>> {
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_DISCOGRAPHY))
        val success = result.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.Success
        return (success.data as EnrichmentData.Discography).albums.map { it.title to it.year }
    }

    @Test
    fun `a provider's discography is served in ascending year order`() = runTest {
        // Given - an engine serving the captured MusicBrainz browse, whose order is 2006, 1994,
        // undated, 2001, 2002, undated, 2002
        val engine = engine(InMemoryEnrichmentCache(), provider(musicBrainzDiscography()))

        // When - enriching the discography
        val albums = albumsOf(engine)

        // Then - the dated albums come first, oldest to newest
        assertEquals(MB_DATED_IN_ORDER, albums.take(MB_DATED_IN_ORDER.size))
    }

    @Test
    fun `an album with no year follows every dated album`() = runTest {
        // Given - the same captured browse, in which two release groups carry no first-release-date
        val engine = engine(InMemoryEnrichmentCache(), provider(musicBrainzDiscography()))

        // When - enriching the discography
        val albums = albumsOf(engine)

        // Then - both undated albums sit after the dated ones, in the order MusicBrainz sent them,
        // and neither is dropped
        assertEquals(MB_DATED_IN_ORDER + MB_UNDATED_IN_ORDER, albums)
    }

    @Test
    fun `a discography carrying no years is served in the provider's own order`() = runTest {
        // Given - an engine serving the captured ListenBrainz response, every album of it undated
        // and the whole list in descending listen count
        val upstream = listenBrainzDiscography()
        val engine = engine(InMemoryEnrichmentCache(), provider(upstream))

        // When - enriching the discography
        val albums = albumsOf(engine)

        // Then - the order is exactly the one ListenBrainz gave
        assertEquals(upstream.albums.map { it.title to it.year }, albums)
    }

    @Test
    fun `a discography read whole from cache is ordered on the way out`() = runTest {
        // Given - a cache holding the captured browse in MusicBrainz's own order and no provider to
        // fall back on, so the request is served entirely from cache
        val engine = engine(cacheHolding(musicBrainzDiscography()))

        // When - enriching the discography
        val albums = albumsOf(engine)

        // Then - the cached entry is served in year order, undated last
        assertEquals(MB_DATED_IN_ORDER + MB_UNDATED_IN_ORDER, albums)
    }

    @Test
    fun `a cached discography read beside an uncached type is ordered on the way out`() = runTest {
        // Given - the same cached discography, requested alongside a type the cache does not hold
        val engine = engine(cacheHolding(musicBrainzDiscography()))

        // When - enriching the discography and the timeline that depends on it
        val result = engine.enrich(
            request,
            setOf(EnrichmentType.ARTIST_DISCOGRAPHY, EnrichmentType.ARTIST_TIMELINE),
        )

        // Then - the discography still comes back in year order, undated last
        val success = result.raw.getValue(EnrichmentType.ARTIST_DISCOGRAPHY) as EnrichmentResult.Success
        val albums = (success.data as EnrichmentData.Discography).albums.map { it.title to it.year }
        assertEquals(MB_DATED_IN_ORDER + MB_UNDATED_IN_ORDER, albums)
    }

    private companion object {
        const val MB_POOL = "musicbrainz-undated-release-group"
        const val LB_POOL = "listenbrainz-top-release-groups"

        /** `D Homemade` precedes `D Fun Pak` because MusicBrainz sent them that way, not by title. */
        val MB_DATED_IN_ORDER: List<Pair<String, Int?>> = listOf(
            "Tenacious Demo" to 1994,
            "Tenacious D" to 2001,
            "D Homemade" to 2002,
            "D Fun Pak" to 2002,
            "The Pick of Destiny" to 2006,
        )

        val MB_UNDATED_IN_ORDER: List<Pair<String, Int?>> = listOf(
            "1998-05-03: Key Club, Los Angeles, CA, USA" to null,
            "The History of Tenacious D: Demos and Pre-Album Tracks" to null,
        )
    }
}
