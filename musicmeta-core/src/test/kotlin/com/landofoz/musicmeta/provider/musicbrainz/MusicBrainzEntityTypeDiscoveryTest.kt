package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.discoverMbidEntityType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a bare MBID costs to identify, asserted rather than described.
 *
 * MusicBrainz has no "what is this id?" endpoint — every lookup is per entity type and the wrong
 * type answers 404, indistinguishable from the right type answering about an id it does not hold.
 * So the probe order *is* the cost model, and the request counts below are the thing a caller
 * budgets against on a 1 req/s limiter.
 */
class MusicBrainzEntityTypeDiscoveryTest {

    private val httpClient = FakeHttpClient()
    private val provider = MusicBrainzProvider(httpClient, RateLimiter(0))

    private fun lookups() = httpClient.requestedUrls.count { it.contains("/ws/2/") }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a recording mbid is identified by the first probe`() = runTest {
        // Given - MusicBrainz holds a recording under the identifier
        httpClient.givenJsonResponse("recording/$RECORDING_MBID", BOHEMIAN_RECORDING)

        // When - the identifier is handed over with no type
        val type = provider.discoverEntityType(RECORDING_MBID)

        // Then - it is a recording, and recording-first spent one request to say so
        assertEquals(MusicBrainzEntityType.RECORDING, type)
        assertEquals(1, lookups())
    }

    @Test
    fun `a release mbid costs the recording probe as well as its own`() = runTest {
        // Given - MusicBrainz holds a release under the identifier and no recording
        httpClient.givenJsonResponse("release/$RELEASE_MBID", RUSH_RELEASE)

        // When - the identifier is handed over with no type
        val type = provider.discoverEntityType(RELEASE_MBID)

        // Then - it is a release, found second, at two requests
        assertEquals(MusicBrainzEntityType.RELEASE, type)
        assertEquals(2, lookups())
    }

    @Test
    fun `an artist mbid is the last type probed`() = runTest {
        // Given - MusicBrainz holds an artist under the identifier and nothing else
        httpClient.givenJsonResponse("artist/$ARTIST_MBID", QUEEN_ARTIST)

        // When - the identifier is handed over with no type
        val type = provider.discoverEntityType(ARTIST_MBID)

        // Then - it is an artist, found third, at three requests
        assertEquals(MusicBrainzEntityType.ARTIST, type)
        assertEquals(3, lookups())
    }

    @Test
    fun `an identifier MusicBrainz holds nothing under is absent, at the full three requests`() = runTest {
        // Given - nothing stubbed, so all three lookups answer 404 as MusicBrainz does for a dead id
        // When - the identifier is handed over with no type
        val type = provider.discoverEntityType(DEAD_MBID)

        // Then - absence is the answer only after every type has said no
        assertNull(type)
        assertEquals(3, lookups())
    }

    @Test
    fun `a miss is paid for once for as long as the call lasts`() = runTest {
        // Given - the call scope one enrich() installs for its provider memos, and an engine
        // discovering through the public entry point inside it
        val engine = engine()

        // When - the same dead identifier is discovered twice in that one call
        val types = withContext(ProviderCallScope()) {
            listOf(engine.discoverMbidEntityType(DEAD_MBID), engine.discoverMbidEntityType(DEAD_MBID))
        }

        // Then - both answers are absence, and the second one cost nothing: the entry point joins
        // the call it was made in rather than starting a cold memo of its own
        assertEquals(listOf(null, null), types)
        assertEquals(3, lookups())
    }

    @Test
    fun `a shed lookup is an outage, not an identifier held under nothing`() = runTest {
        // Given - MusicBrainz shedding the recording lookup, as it does with a 503
        httpClient.givenHttpResult("recording/$RECORDING_MBID", HttpResult.ServerError(503))

        // When - the identifier is handed over with no type
        val thrown = runCatching { provider.discoverEntityType(RECORDING_MBID) }.exceptionOrNull()

        // Then - it propagates instead of answering, because a probe that never got an answer
        // cannot say the identifier names nothing
        assertTrue(thrown is IOException)
        // Then - the release and artist probes were never reached: the ladder is for absence, and
        // walking it on an outage would spend two more requests to reach a wrong answer
        assertEquals(1, lookups())
    }

    @Test
    fun `the engine answers from the MusicBrainz provider it was built with`() = runTest {
        // Given - an engine whose identity provider is this MusicBrainz, sharing its limiter
        httpClient.givenJsonResponse("recording/$RECORDING_MBID", BOHEMIAN_RECORDING)
        val engine = engine()

        // When - the identifier is handed to the public entry point
        val type = engine.discoverMbidEntityType(RECORDING_MBID)

        // Then - it is the same answer at the same cost, without a second limiter for MusicBrainz
        assertEquals(MusicBrainzEntityType.RECORDING, type)
        assertEquals(1, lookups())
    }

    private companion object {
        const val RECORDING_MBID = "5cf87954-1402-45f0-bbcf-e957eb332da7"
        const val RELEASE_MBID = "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4"
        const val ARTIST_MBID = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"

        /** Probed 2026-08-12: recording, release and artist lookups all answered 404 for this id. */
        const val DEAD_MBID = "60d043af-b702-30d9-b6c0-0688d7863b14"

        // captured 2026-08-12: GET /ws/2/recording/5cf87954-...?inc=artists+releases+release-groups, trimmed
        const val BOHEMIAN_RECORDING = """
            {
              "id": "5cf87954-1402-45f0-bbcf-e957eb332da7",
              "title": "Bohemian Rhapsody",
              "length": 157000,
              "isrcs": [],
              "artist-credit": [
                {
                  "name": "Queen",
                  "joinphrase": "",
                  "artist": { "id": "0383dadf-2a4e-4d10-a46a-e9e041da8eb3", "name": "Queen" }
                }
              ]
            }
        """

        // captured 2026-08-12: GET /ws/2/release/4ebc64a2-...?inc=artist-credits+release-groups, trimmed
        const val RUSH_RELEASE = """
            {
              "id": "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4",
              "title": "A Rush of Blood to the Head",
              "date": "2008-06-11",
              "country": "JP",
              "status": "Official",
              "artist-credit": [
                {
                  "name": "Coldplay",
                  "joinphrase": "",
                  "artist": { "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234", "name": "Coldplay" }
                }
              ],
              "release-group": {
                "id": "120c786d-a3b2-3c19-b4ff-2b7b3b4435bf",
                "title": "A Rush of Blood to the Head",
                "primary-type": "Album"
              }
            }
        """

        // captured 2026-08-12: GET /ws/2/artist/0383dadf-...?inc=tags+genres+aliases+url-rels, trimmed
        const val QUEEN_ARTIST = """
            {
              "id": "0383dadf-2a4e-4d10-a46a-e9e041da8eb3",
              "name": "Queen",
              "sort-name": "Queen",
              "type": "Group",
              "country": "GB",
              "disambiguation": "UK rock group"
            }
        """
    }
}
