package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Identity resolution is gated on what the request already knows, and on a track request
 * `musicBrainzId` does not mean what the gate reads it to mean.
 *
 * `EnrichmentIdentifiers.musicBrainzId` is polymorphic: a release id on `ForAlbum`, an artist id on
 * `ForArtist`, a **recording** id on `ForTrack` (`MusicBrainzMapper.toTrackIdentifiers`). So a
 * recording id satisfies nothing album-scoped — cover art least of all, which needs a release-group
 * id — and a track request carrying one still has a gap only resolution can close. Read the gate
 * without that, and supplying a correct identifier returns strictly less than supplying none.
 */
class TrackIdentityGateTest {

    private lateinit var httpClient: FakeHttpClient

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        httpClient.givenJsonResponse(RECORDING_SEARCH, POOL)
        httpClient.givenJsonResponse(RECORDING_LOOKUP, RECORDING)
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(
            listOf(
                MusicBrainzProvider(httpClient, RateLimiter(0)),
                CoverArtArchiveProvider(httpClient, RateLimiter(0)),
            ),
        ),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a track request carrying only a recording mbid resolves identity, and its art with it`() = runTest {
        // Given - a caller naming the exact recording, which is all a Last.fm or ListenBrainz pick carries
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST, mbid = RECORDING_MBID)

        // When - the track's art and metadata are enriched
        val results = engine().enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.TRACK_METADATA))

        // Then - resolution filled the release-group id nothing else fills, and CAA served the art.
        // It cost no upstream request either: resolution asks MusicBrainz for the same recording the
        // types want, and the enricher's per-call memo answers all of them from one lookup.
        assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
        assertEquals(RELEASE_GROUP_MBID, results.identity.identifiers.musicBrainzReleaseGroupId)
        assertTrue(
            "expected art, got ${results.raw[EnrichmentType.ALBUM_ART]}",
            results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success,
        )
        assertEquals(1, httpClient.requestedUrls.count { it.contains(RECORDING_LOOKUP) })
        assertEquals(0, httpClient.requestedUrls.count { it.contains(RECORDING_SEARCH) })
    }

    @Test
    fun `supplying the recording mbid returns no less than supplying nothing`() = runTest {
        // Given - the same track by name alone, which is the run the MBID one has to match
        val byName = EnrichmentRequest.forTrack(TITLE, ARTIST)
        val types = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.TRACK_METADATA)

        // When - both requests are enriched against the same upstream
        val nameOnly = engine().enrich(byName, types)
        val withMbid = engine().enrich(EnrichmentRequest.forTrack(TITLE, ARTIST, mbid = RECORDING_MBID), types)

        // Then - the art and the identity block are there either way; the identifier only adds
        assertTrue(nameOnly.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
        assertTrue(
            "expected art with the MBID too, got ${withMbid.raw[EnrichmentType.ALBUM_ART]}",
            withMbid.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success,
        )
        assertNotNull(nameOnly.identity)
        assertNotNull(withMbid.identity)
    }

    @Test
    fun `a track request already carrying a release-group id does not re-resolve identity`() = runTest {
        // Given - a caller who already knows both ids, so there is no gap left to close
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST).withIdentifiers(
            EnrichmentIdentifiers(
                musicBrainzId = RECORDING_MBID,
                musicBrainzReleaseGroupId = RELEASE_GROUP_MBID,
            ),
        )

        // When - the same two types are enriched
        val results = engine().enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.TRACK_METADATA))

        // Then - resolution was never attempted, and the art came back anyway. Supplying more must
        // never cost a call.
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, results.identity.status)
        assertTrue(results.raw[EnrichmentType.ALBUM_ART] is EnrichmentResult.Success)
    }

    @Test
    fun `a track request whose types declare no identifier resolves nothing, and spends nothing`() = runTest {
        // Given - a track known by MBID and a type answered from a name, declaring no identifier
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST, mbid = RECORDING_MBID)

        // When - only that type is enriched
        val results = engine().enrich(request, setOf(EnrichmentType.TRACK_METADATA))

        // Then - no resolution, because nothing in the request's chains wants an identifier it lacks.
        // The track rule qualifies what MUSICBRAINZ_ID means rather than short-circuiting the scan,
        // so it cannot bill a request that had no identifier gap to close.
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, results.identity.status)
    }

    @Test
    fun `an album request carrying a release mbid still skips identity resolution`() = runTest {
        // Given - an album known by MBID, where musicBrainzId already means the release CAA wants
        httpClient.givenJsonResponse("release/rel-1?", RELEASE)
        val request = EnrichmentRequest.forAlbum("Metallica", ARTIST, mbid = "rel-1")

        // When - a type whose chain declares MUSICBRAINZ_ID is enriched
        val results = engine().enrich(request, setOf(EnrichmentType.ALBUM_ART))

        // Then - the gate is unmoved for album requests, which is where it was already right
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, results.identity.status)
    }

    @Test
    fun `an artist request carrying an artist mbid still skips identity resolution`() = runTest {
        // Given - an artist known by MBID, and a type MusicBrainz answers from that id alone
        httpClient.givenJsonResponse("artist/art-1?", ARTIST_LOOKUP)
        val request = EnrichmentRequest.forArtist(ARTIST, mbid = "art-1")

        // When - band members are enriched
        val results = engine().enrich(request, setOf(EnrichmentType.BAND_MEMBERS))

        // Then - the gate is unmoved for artist requests too
        assertEquals(CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED, results.identity.status)
    }

    private companion object {
        const val TITLE = "Enter Sandman"
        const val ARTIST = "Metallica"
        const val RECORDING_MBID = "rec-studio"
        const val RELEASE_GROUP_MBID = "rg-studio"
        const val RECORDING_SEARCH = "recording?query"
        const val RECORDING_LOOKUP = "recording/rec-studio?"

        val POOL = """
            {
              "recordings": [{
                "id": "rec-studio", "score": 100, "title": "Enter Sandman",
                "tags": [{"name": "heavy metal", "count": 7}],
                "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
                "releases": [{
                  "id": "rel-1", "status": "Official",
                  "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
                }]
              }]
            }
        """.trimIndent()

        val RECORDING = """
            {
              "id": "rec-studio",
              "title": "Enter Sandman",
              "length": 331560,
              "tags": [{"name": "heavy metal", "count": 7}],
              "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
              "releases": [{
                "id": "rel-1", "status": "Official",
                "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
              }]
            }
        """.trimIndent()

        val RELEASE = """
            {
              "id": "rel-1",
              "title": "Metallica",
              "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
              "release-group": {"id": "rg-studio", "primary-type": "Album"}
            }
        """.trimIndent()

        val ARTIST_LOOKUP = """
            {
              "id": "art-1",
              "name": "Metallica",
              "type": "Group",
              "relations": [{
                "type": "member of band", "direction": "backward",
                "artist": {"id": "m-1", "name": "James Hetfield"},
                "attributes": ["lead vocals"]
              }]
            }
        """.trimIndent()
    }
}
