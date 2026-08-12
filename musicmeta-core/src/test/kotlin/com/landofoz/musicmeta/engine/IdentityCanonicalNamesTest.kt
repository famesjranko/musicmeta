package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What `IdentityResolution` names, beside the identifiers it resolved.
 *
 * A caller holding only an MBID has no name for the entity, and the engine already learns one to
 * hand the name-search providers. These pin that the same names reach the consumer, that a caller
 * who supplied their own still learns the canonical forms, and that a resolution which named
 * nothing says so rather than echoing the request back.
 */
class IdentityCanonicalNamesTest {

    private val httpClient = FakeHttpClient()
    private val cache = FakeEnrichmentCache()

    /**
     * Art keyed on a release-group, the shape Cover Art Archive has: a recording MBID cannot stand
     * in for one, so identity resolution runs even for a request that named its entity itself.
     */
    private val art = FakeProvider(
        id = "art",
        capabilities = listOf(
            ProviderCapability(
                EnrichmentType.ALBUM_ART,
                100,
                identifierRequirement = IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID,
            ),
        ),
    )

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(MusicBrainzProvider(httpClient, RateLimiter(0)), art)),
        cache,
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a track request carrying only an mbid comes back naming the recording`() = runTest {
        // Given - a recording MusicBrainz holds under the caller's identifier, credited to two artists
        httpClient.givenJsonResponse("recording/$UNDER_PRESSURE_MBID", UNDER_PRESSURE_RECORDING)

        // When - the request names that identifier and no title or artist at all
        val results = engine().enrich(
            EnrichmentRequest.forTrackByMbid(UNDER_PRESSURE_MBID),
            setOf(EnrichmentType.GENRE),
        )

        // Then - the identity carries the canonical title and the joined artist-credit, so a caller
        // holding only the identifier can label what it named
        assertEquals(IdentityMatch.RESOLVED, results.identity?.match)
        assertEquals("Under Pressure", results.identity?.title)
        assertEquals("Queen & David Bowie", results.identity?.artist)
    }

    @Test
    fun `an artist request carries the artist name as the title`() = runTest {
        // Given - an artist MusicBrainz holds under the caller's identifier
        httpClient.givenJsonResponse("artist/$QUEEN_MBID", QUEEN_ARTIST)

        // When - the request names that identifier and no name
        val results = engine().enrich(
            EnrichmentRequest.forArtistByMbid(QUEEN_MBID),
            setOf(EnrichmentType.GENRE),
        )

        // Then - the name arrives as the title, the vocabulary an artist SearchCandidate already
        // uses, and no artist credit is invented beside it
        assertEquals("Queen", results.identity?.title)
        assertNull(results.identity?.artist)
    }

    @Test
    fun `a caller who named the entity themselves still learns its canonical names`() = runTest {
        // Given - the same recording, whose canonical title differs from the one the caller asked for
        httpClient.givenJsonResponse("recording/$UNDER_PRESSURE_MBID", UNDER_PRESSURE_RECORDING)

        // When - the caller names the variant they meant alongside the identifier, for a type whose
        // identifier the recording MBID cannot supply
        val results = engine().enrich(
            EnrichmentRequest.forTrack("Under Pressure (live at Wembley)", "Queen", mbid = UNDER_PRESSURE_MBID),
            setOf(EnrichmentType.ALBUM_ART),
        )

        // Then - the identity reports what the identifier named, which is not what the caller asked
        // with; the request's own fields still hold the caller's variant
        assertEquals("Under Pressure", results.identity?.title)
        assertEquals("Queen & David Bowie", results.identity?.artist)
    }

    @Test
    fun `an mbid MusicBrainz holds nothing under names nothing`() = runTest {
        // Given - nothing stubbed, so every MusicBrainz lookup answers 404 as it does for a dead id
        // When - a request naming only that identifier is enriched
        val results = engine().enrich(
            EnrichmentRequest.forTrackByMbid(DEAD_MBID),
            setOf(EnrichmentType.GENRE),
        )

        // Then - both names stay null rather than being filled with a guess or with the blank
        // request the caller made
        assertNull(results.identity?.title)
        assertNull(results.identity?.artist)
    }

    private companion object {
        const val UNDER_PRESSURE_MBID = "6f903161-8757-4363-a6ab-54bfe1149bb8"
        const val QUEEN_MBID = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"
        const val DEAD_MBID = "60d043af-b702-30d9-b6c0-0688d7863b14"

        // captured 2026-08-12: GET /ws/2/recording/6f903161-...?inc=artists+releases+release-groups+isrcs, trimmed
        const val UNDER_PRESSURE_RECORDING = """
            {
              "id": "6f903161-8757-4363-a6ab-54bfe1149bb8",
              "title": "Under Pressure",
              "length": 235400,
              "isrcs": ["GBUM71106916"],
              "artist-credit": [
                {
                  "name": "Queen",
                  "joinphrase": " & ",
                  "artist": { "id": "0383dadf-2a4e-4d10-a46a-e9e041da8eb3", "name": "Queen" }
                },
                {
                  "name": "David Bowie",
                  "joinphrase": "",
                  "artist": { "id": "5441c29d-3602-4898-b1a1-b77fa23b8e50", "name": "David Bowie" }
                }
              ],
              "releases": [
                {
                  "id": "46a1cce2-a0a8-4816-9c7c-f48e0537032b",
                  "title": "My Generation - The Soundtrack of Our Lives, Part One",
                  "date": "2005-10",
                  "release-group": {
                    "id": "13ed01fd-af02-3030-afc1-17e62c53eb02",
                    "title": "My Generation - The Soundtrack of Our Lives, Part One",
                    "primary-type": "Album",
                    "first-release-date": "2005-10"
                  }
                }
              ]
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
              "disambiguation": "UK rock group",
              "genres": [ { "name": "art rock", "count": 15 } ]
            }
        """
    }
}
