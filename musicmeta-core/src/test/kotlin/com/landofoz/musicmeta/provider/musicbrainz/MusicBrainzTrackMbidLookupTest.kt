package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
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
import org.junit.Assert.assertNull
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

    private fun notFoundOf(result: EnrichmentResult?): EnrichmentResult.NotFound {
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
        return result as EnrichmentResult.NotFound
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
    fun `a recording mbid MusicBrainz does not hold resolves by name, as a request carrying none does`() = runTest {
        // Given - a pool that resolves happily, and an MBID whose lookup answers 404
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - the track is enriched with that MBID
        val result = provider.enrich(trackWithMbid(DEAD_MBID), EnrichmentType.GENRE)

        // Then - the name search answers, at its own confidence. An identifier MusicBrainz holds
        // nothing under names no recording, so no recording exists for the answer to be unfaithful
        // to - the wrong-recording risk this path guards is the property of an identifier that
        // resolves, and a stale third-party id would otherwise cost the request every provider.
        val success = successOf(result)
        assertEquals(LIVE_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(ConfidenceCalculator.searchScore(POOL_SCORE), success.confidence, TOLERANCE)
    }

    @Test
    fun `a lookup body the parser cannot read is not traded for a search hit`() = runTest {
        // Given - a recording MusicBrainz answers for but the parser rejects, and a resolvable pool
        httpClient.givenJsonResponse(STUDIO_LOOKUP, UNPARSEABLE_LOOKUP_RESPONSE)
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - the track is enriched with that MBID
        val result = provider.enrich(trackWithMbid(STUDIO_MBID), EnrichmentType.GENRE)

        // Then - MusicBrainz holds the recording the caller named, so a different one cannot stand
        // in for it however well it ranks, and nothing is suggested for a choice already made
        assertNull(notFoundOf(result).suggestions)
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
    fun `the identity a consumer reads names the recording its payload describes`() = runTest {
        // Given - two identical searches MusicBrainz answers in different orders, whose candidates
        // tie on every ranking signal, so pool order alone decides which one the ranking keeps
        httpClient.givenJsonResponsesInTurn(RECORDING_SEARCH, TIED_POOL_A_FIRST, TIED_POOL_B_FIRST)

        // When - one call resolves identity and then fans out to the type that echoes it back
        val results = engine().enrich(
            EnrichmentRequest.forTrack(TITLE, ARTIST),
            setOf(EnrichmentType.TRACK_METADATA),
        )

        // Then - both answer with the recording the call's first search picked. Two searches would
        // rank two differently-ordered pools, and a consumer would be handed an identity naming one
        // recording and metadata describing the other.
        val success = successOf(results.raw[EnrichmentType.TRACK_METADATA])
        assertEquals(CANDIDATE_A, results.identity?.identifiers?.musicBrainzId)
        assertEquals(CANDIDATE_A, success.resolvedIdentifiers?.musicBrainzId)
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
    fun `a dead caller mbid costs one lookup for the whole call, not one per type`() = runTest {
        // Given - a flatly wrong recording MBID, the shape a stale third-party id has, and a pool of
        // recordings for the track it names
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - two track types are enriched in one call
        val results = engine().enrich(
            trackWithMbid(DEAD_MBID),
            setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA),
        )

        // Then - both types answer with the recording the search resolved, and MusicBrainz was asked
        // about the identifier once: its answer is that it holds no such recording, which no later
        // type can get a different answer to. The pool the fall-back resolves from is held too, so a
        // second type cannot rank a differently-ordered copy of it and answer with another take.
        assertEquals(LIVE_MBID, successOf(results.raw[EnrichmentType.TRACK_METADATA]).resolvedIdentifiers?.musicBrainzId)
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        assertEquals(1, recordingLookups())
        assertEquals(1, searches())
    }

    @Test
    fun `a dead mbid alongside a release-group id resolves too, with no identity gate to help`() = runTest {
        // Given - the same dead recording MBID, on a request that also names a release group, which
        // is what makes every type's identifiers complete and skips identity resolution entirely
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)
        val request = trackWithMbid(DEAD_MBID)
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = DEAD_MBID, musicBrainzReleaseGroupId = "rg-live"))

        // When - two track types are enriched in one call
        val results = engine().enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA))

        // Then - each type falls back on its own, and both the absence and the pool it falls back to
        // are held for the call, so the second type re-asks neither question
        assertEquals(LIVE_MBID, successOf(results.raw[EnrichmentType.TRACK_METADATA]).resolvedIdentifiers?.musicBrainzId)
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        assertEquals(1, recordingLookups())
        assertEquals(1, searches())
    }

    @Test
    fun `credits for a dead mbid suggest nothing, as credits for no mbid do`() = runTest {
        // Given - a dead recording MBID, and a pool of recordings for the track it names
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - credits are enriched for that identifier, with no identity resolution ahead of it
        // to replace it with one that resolves
        val result = provider.enrich(trackWithMbid(DEAD_MBID), EnrichmentType.CREDITS)

        // Then - credits are read off a lookup and never off a search, so an identifier naming
        // nothing leaves this path where a request naming no recording already sits
        assertNull(notFoundOf(result).suggestions)
        assertEquals(0, searches())
    }

    @Test
    fun `a recording MusicBrainz holds but credits nobody on suggests nothing`() = runTest {
        // Given - a recording the lookup finds, carrying no relations to credit anyone from
        httpClient.givenJsonResponse(STUDIO_LOOKUP, NO_CREDITS_LOOKUP_RESPONSE)
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - credits are enriched for it
        val result = provider.enrich(trackWithMbid(STUDIO_MBID), EnrichmentType.CREDITS)

        // Then - the answer is "this recording, and it credits nobody". Other recordings would
        // answer "did you mean a different track?", which is a question nobody asked: the caller's
        // identifier resolved, so there is nothing left to choose between.
        assertNull(notFoundOf(result).suggestions)
        assertEquals(0, searches())
    }

    @Test
    fun `credits for a track naming no recording suggest nothing, and cost nothing`() = runTest {
        // Given - a track named by title and artist alone, which credits cannot be looked up by
        httpClient.givenJsonResponse(RECORDING_SEARCH, LIVE_ONLY_POOL)

        // When - credits are enriched
        val result = provider.enrich(EnrichmentRequest.forTrack(TITLE, ARTIST), EnrichmentType.CREDITS)

        // Then - no identifier is not a lookup that missed. Nothing was asked of MusicBrainz, so
        // nothing is spent asking it something else.
        assertNull(notFoundOf(result).suggestions)
        assertEquals(0, searches())
        assertEquals(0, recordingLookups())
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
        const val DEAD_MBID = "rec-unknown"
        const val CANDIDATE_A = "rec-candidate-a"
        const val CANDIDATE_B = "rec-candidate-b"
        const val POOL_SCORE = 92
        const val RECORDING_SEARCH = "recording?query"
        const val RECORDING_LOOKUP = "recording/"
        const val STUDIO_LOOKUP = "recording/rec-studio?"
        const val LIVE_LOOKUP = "recording/rec-live-1?"
        const val TOLERANCE = 0.0001f

        fun trackWithMbid(mbid: String) = EnrichmentRequest.forTrack(TITLE, ARTIST, mbid = mbid)

        /**
         * Two masterings of one track, tied on every tier `pickBestRecording` ranks — same exact
         * title, neither a video, both unmarked, both on an Official Album release-group — so the
         * ranking keeps whichever the pool listed first. Where a recording sits in a pool is
         * upstream's to decide and shifts between identical calls
         * ([MusicBrainzApi.CANONICAL_SEARCH_LIMIT] holds that measurement), so the order is the
         * variable here and the pool is not.
         */
        fun tiedPool(first: String, second: String) = """
            {
              "recordings": [
                ${tiedRecording(first)},
                ${tiedRecording(second)}
              ]
            }
        """.trimIndent()

        fun tiedRecording(id: String) = """
            {
              "id": "$id", "score": 100, "title": "Enter Sandman", "length": 331560,
              "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
              "releases": [{
                "status": "Official",
                "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
              }]
            }
        """.trimIndent()

        val TIED_POOL_A_FIRST = tiedPool(CANDIDATE_A, CANDIDATE_B)
        val TIED_POOL_B_FIRST = tiedPool(CANDIDATE_B, CANDIDATE_A)

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
         * A 200 the parser rejects: [MusicBrainzParser.parseLookupRecording] requires an `id`, and
         * this body carries none. MusicBrainz answering at all is what separates this from a 404 —
         * it holds the recording, so the request stays on the lookup path.
         */
        const val UNPARSEABLE_LOOKUP_RESPONSE = """{"title": "Enter Sandman", "length": 331560}"""

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
         * A recording MusicBrainz holds and has no relations for — the shape that separates "no
         * such recording" from "this recording, credited to nobody". MusicBrainz omits `relations`
         * entirely rather than sending an empty array.
         */
        val NO_CREDITS_LOOKUP_RESPONSE = """
            {
              "id": "rec-studio",
              "title": "Enter Sandman",
              "length": 331560,
              "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}]
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
