package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A recording MBID that reached the enricher from outside this call names the recording the answer
 * must describe.
 *
 * The distinction drawn here is between an MBID the caller (or a foreign identity provider) supplied
 * and one this call's own recording search picked moments earlier. The first is an instruction and
 * is looked up; the second is the engine's echo of a choice already made, and looking *it* up would
 * change which release-group answers every name-only track request. Only the enricher can tell them
 * apart, because only it knows what it searched for this call.
 */
class MusicBrainzTrackMbidLookupTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    private fun searches() = httpClient.requestedUrls.count { it.contains(RECORDING_SEARCH) }

    private fun recordingLookups() = httpClient.requestedUrls.count { it.contains(RECORDING_LOOKUP) }

    private fun successOf(result: EnrichmentResult?): EnrichmentResult.Success {
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return result as EnrichmentResult.Success
    }

    @Test
    fun `a caller-supplied mbid resolves that recording, not the one the search ranks first`() = runTest {
        // Given - a pool holding only live takes, and a caller naming the studio recording it does not contain
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)
        httpClient.givenJsonResponse(STUDIO_LOOKUP, STUDIO_LOOKUP_RESPONSE)

        // When - the track is enriched with that MBID on the request
        val result = provider.enrich(trackWithMbid(STUDIO_MBID), EnrichmentType.GENRE)

        // Then - the caller's recording answered, at id-lookup confidence, with no search run at all
        val success = successOf(result)
        assertEquals(STUDIO_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(ConfidenceCalculator.idBasedLookup(), success.confidence, TOLERANCE)
        assertEquals(0, searches())
    }

    @Test
    fun `the lookup path fills the album title and release-group a search hit would have carried`() = runTest {
        // Given - the same caller-supplied MBID, and TRACK_METADATA, which exposes the album title
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)
        httpClient.givenJsonResponse(STUDIO_LOOKUP, STUDIO_LOOKUP_RESPONSE)

        // When - the track's metadata is enriched
        val result = provider.enrich(trackWithMbid(STUDIO_MBID), EnrichmentType.TRACK_METADATA)

        // Then - the consumer-visible fields the lookup path decides are the studio recording's
        val success = successOf(result)
        val data = success.data as EnrichmentData.TrackMetadata
        assertEquals("Metallica", data.albumTitle)
        assertEquals(331560L, data.durationMs)
        assertEquals("rg-studio", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
        assertEquals(ConfidenceCalculator.idBasedLookup(), success.confidence, TOLERANCE)
    }

    @Test
    fun `a recording mbid MusicBrainz does not hold is NotFound, never a silent fall back to the search`() = runTest {
        // Given - a pool that would resolve happily, and an MBID whose lookup answers 404
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - the track is enriched with that MBID
        val result = provider.enrich(trackWithMbid("rec-unknown"), EnrichmentType.GENRE)

        // Then - the miss is reported rather than answered by whatever the search would have picked
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
        assertEquals(0, searches())
    }

    @Test
    fun `an mbid this call's own search picked keeps the search path, unchanged`() = runTest {
        // Given - a name-only track request, so identity resolution has to search for the recording
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)
        httpClient.givenJsonResponse(LIVE_LOOKUP, STUDIO_LOOKUP_RESPONSE)

        // When - two types are enriched in one call, the second seeing the MBID identity merged in
        val results = engine().enrich(
            EnrichmentRequest.forTrack(TITLE, ARTIST),
            setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA),
        )

        // Then - the ranking's own pick still answers, at search-score confidence and off the pool
        val success = successOf(results.raw[EnrichmentType.TRACK_METADATA])
        assertEquals(LIVE_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals("rg-live", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
        assertEquals(ConfidenceCalculator.searchScore(POOL_SCORE), success.confidence, TOLERANCE)
        assertEquals(0, recordingLookups())
    }

    @Test
    fun `a track request carrying an mbid looks the recording up once, not once per type`() = runTest {
        // Given - a caller-supplied MBID and the three default track types MusicBrainz can answer
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)
        httpClient.givenJsonResponse(STUDIO_LOOKUP, STUDIO_LOOKUP_RESPONSE)

        // When - all three are enriched in one call
        val results = engine().enrich(
            trackWithMbid(STUDIO_MBID),
            setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA, EnrichmentType.CREDITS),
        )

        // Then - one lookup served credits and metadata alike, on a 1 req/s limiter
        assertTrue(results.raw[EnrichmentType.CREDITS] is EnrichmentResult.Success)
        assertEquals(STUDIO_MBID, successOf(results.raw[EnrichmentType.GENRE]).resolvedIdentifiers?.musicBrainzId)
        assertEquals(1, recordingLookups())
    }

    @Test
    fun `the album hint picks the release-group even when the lookup embeds a compilation first`() = runTest {
        // Given - a lookup whose releases run promo, compilation, studio album, in that order
        httpClient.givenJsonResponse(STUDIO_LOOKUP, REORDERED_LOOKUP_RESPONSE)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST, album = "Metallica", mbid = STUDIO_MBID)

        // When - the track's metadata is enriched
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - the requested album wins on tier 0, ahead of an Official Album earlier in the array
        val success = successOf(result)
        assertEquals("rg-studio", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
        assertEquals("Metallica", (success.data as EnrichmentData.TrackMetadata).albumTitle)
    }

    @Test
    fun `without an album hint the array order decides, and tier 1 takes the first Official Album`() = runTest {
        // Given - the same reordered lookup, and a request naming no album, so there is no tier 0
        httpClient.givenJsonResponse(STUDIO_LOOKUP, REORDERED_LOOKUP_RESPONSE)

        // When - the track's metadata is enriched
        val result = provider.enrich(trackWithMbid(STUDIO_MBID), EnrichmentType.TRACK_METADATA)

        // Then - the compilation wins, because it is the first Official Album MusicBrainz listed.
        // Pinned rather than fixed: the order is upstream's to change, and an album hint is the only
        // thing that makes this deterministic — which is what the test above covers.
        val success = successOf(result)
        assertEquals("rg-hits", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    private companion object {
        const val TITLE = "Enter Sandman"
        const val ARTIST = "Metallica"
        const val STUDIO_MBID = "rec-studio"
        const val LIVE_MBID = "rec-live-1"
        const val POOL_SCORE = 92
        const val RECORDING_SEARCH = "recording?query"
        const val RECORDING_LOOKUP = "recording/"
        const val STUDIO_LOOKUP = "recording/rec-studio?"
        const val LIVE_LOOKUP = "recording/rec-live-1?"
        const val TOLERANCE = 0.0001f

        fun trackWithMbid(mbid: String) = EnrichmentRequest.forTrack(TITLE, ARTIST, mbid = mbid)

        /**
         * What the production query really returns for a heavily-covered track: every candidate a
         * live take, the studio original nowhere in the pool
         * (`scripts/probes/recording-pool-filter-probe.sh`).
         */
        val LIVE_ONLY_POOL = """
            {
              "recordings": [
                {
                  "id": "rec-live-1", "score": 92, "title": "Enter Sandman",
                  "disambiguation": "live, 1992-01-11: Arco Arena, Sacramento, CA, US",
                  "tags": [{"name": "thrash metal", "count": 3}],
                  "releases": [
                    {
                      "status": "Official",
                      "release-group": {"id": "rg-live", "title": "Live Shit", "primary-type": "Album"}
                    }
                  ]
                },
                {
                  "id": "rec-live-2", "score": 92, "title": "Enter Sandman",
                  "disambiguation": "live at Moscow"
                }
              ]
            }
        """.trimIndent()

        /**
         * A `recording/<mbid>` lookup at the widened `inc=`: the recording at the top level, its
         * releases each carrying a release-group, and the artist relations credits reads.
         */
        val STUDIO_LOOKUP_RESPONSE = """
            {
              "id": "rec-studio",
              "title": "Enter Sandman",
              "length": 331560,
              "video": false,
              "isrcs": ["USEE10200008"],
              "tags": [{"name": "heavy metal", "count": 7}],
              "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
              "releases": [
                {
                  "status": "Official",
                  "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
                }
              ],
              "relations": [
                {
                  "target-type": "artist",
                  "type": "producer",
                  "artist": {"id": "art-rock", "name": "Bob Rock"}
                }
              ]
            }
        """.trimIndent()

        /**
         * The same recording with its `releases` in a different order from the search's: a promo
         * single first, then a compilation, then the studio album. MusicBrainz owns that order, so
         * the two tests above pin what each tier does with it rather than assuming it is stable.
         */
        val REORDERED_LOOKUP_RESPONSE = """
            {
              "id": "rec-studio",
              "title": "Enter Sandman",
              "length": 331560,
              "tags": [{"name": "heavy metal", "count": 7}],
              "releases": [
                {
                  "status": "Promotion",
                  "release-group": {"id": "rg-promo", "title": "Enter Sandman", "primary-type": "Single"}
                },
                {
                  "status": "Official",
                  "release-group": {"id": "rg-hits", "title": "The Best Of", "primary-type": "Album"}
                },
                {
                  "status": "Official",
                  "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
                }
              ]
            }
        """.trimIndent()
    }
}
