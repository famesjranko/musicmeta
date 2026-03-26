package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentEngineExtensionsTest {

    /** Minimal fake engine that records calls and returns configurable results. */
    private class FakeEngine : EnrichmentEngine {
        var lastRequest: EnrichmentRequest? = null
        var lastTypes: Set<EnrichmentType>? = null
        var lastForceRefresh: Boolean = false
        var resultsToReturn: Map<EnrichmentType, EnrichmentResult> = emptyMap()
        var identityToReturn: IdentityResolution? = null

        override suspend fun enrich(request: EnrichmentRequest, types: Set<EnrichmentType>, forceRefresh: Boolean): EnrichmentResults {
            lastRequest = request
            lastTypes = types
            lastForceRefresh = forceRefresh
            return EnrichmentResults(resultsToReturn, types, identityToReturn)
        }

        override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> = emptyList()
        override fun getProviders(): List<ProviderInfo> = emptyList()
        override val cache: EnrichmentCache = InMemoryEnrichmentCache()
        override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) {}
        override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean = false
        override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) {}
    }

    // --- artistProfile ---

    @Test fun `artistProfile calls enrich with ForArtist and default types`() = runTest {
        // Given
        val engine = FakeEngine()
        engine.resultsToReturn = mapOf(
            EnrichmentType.GENRE to EnrichmentResult.Success(
                EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.9f,
            ),
        )

        // When
        val profile = engine.artistProfile("Radiohead")

        // Then
        assertEquals("Radiohead", profile.name)
        assertTrue(engine.lastRequest is EnrichmentRequest.ForArtist)
        assertEquals("Radiohead", (engine.lastRequest as EnrichmentRequest.ForArtist).name)
        assertEquals(EnrichmentRequest.DEFAULT_ARTIST_TYPES, engine.lastTypes)
    }

    @Test fun `artistProfile passes MBID to request`() = runTest {
        val engine = FakeEngine()

        engine.artistProfile("Radiohead", mbid = "abc-123")

        val req = engine.lastRequest as EnrichmentRequest.ForArtist
        assertEquals("abc-123", req.identifiers.musicBrainzId)
    }

    @Test fun `artistProfile accepts custom type set`() = runTest {
        val engine = FakeEngine()
        val customTypes = setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO)

        engine.artistProfile("Radiohead", types = customTypes)

        assertEquals(customTypes, engine.lastTypes)
    }

    @Test fun `artistProfile from SearchCandidate uses candidate MBID`() = runTest {
        val engine = FakeEngine()
        val candidate = SearchCandidate(
            title = "Radiohead",
            artist = null,
            year = null,
            country = "GB",
            releaseType = "Group",
            score = 95,
            thumbnailUrl = null,
            identifiers = EnrichmentIdentifiers(musicBrainzId = "candidate-mbid"),
            provider = "musicbrainz",
            disambiguation = "British rock band",
        )

        val profile = engine.artistProfile(candidate)

        assertEquals("Radiohead", profile.name)
        val req = engine.lastRequest as EnrichmentRequest.ForArtist
        assertEquals("candidate-mbid", req.identifiers.musicBrainzId)
    }

    // --- albumProfile ---

    @Test fun `albumProfile calls enrich with ForAlbum and default types`() = runTest {
        val engine = FakeEngine()

        val profile = engine.albumProfile("OK Computer", "Radiohead")

        assertEquals("OK Computer", profile.title)
        assertEquals("Radiohead", profile.artist)
        assertTrue(engine.lastRequest is EnrichmentRequest.ForAlbum)
        val req = engine.lastRequest as EnrichmentRequest.ForAlbum
        assertEquals("OK Computer", req.title)
        assertEquals("Radiohead", req.artist)
        assertEquals(EnrichmentRequest.DEFAULT_ALBUM_TYPES, engine.lastTypes)
    }

    @Test fun `albumProfile from SearchCandidate uses candidate MBID and artist`() = runTest {
        val engine = FakeEngine()
        val candidate = SearchCandidate(
            title = "OK Computer",
            artist = "Radiohead",
            year = "1997",
            country = "GB",
            releaseType = "Album",
            score = 100,
            thumbnailUrl = null,
            identifiers = EnrichmentIdentifiers(musicBrainzId = "album-mbid"),
            provider = "musicbrainz",
        )

        val profile = engine.albumProfile(candidate)

        assertEquals("OK Computer", profile.title)
        assertEquals("Radiohead", profile.artist)
        val req = engine.lastRequest as EnrichmentRequest.ForAlbum
        assertEquals("album-mbid", req.identifiers.musicBrainzId)
    }

    // --- trackProfile ---

    @Test fun `trackProfile calls enrich with ForTrack and default types`() = runTest {
        val engine = FakeEngine()

        val profile = engine.trackProfile("Creep", "Radiohead", album = "Pablo Honey")

        assertEquals("Creep", profile.title)
        assertEquals("Radiohead", profile.artist)
        assertTrue(engine.lastRequest is EnrichmentRequest.ForTrack)
        val req = engine.lastRequest as EnrichmentRequest.ForTrack
        assertEquals("Creep", req.title)
        assertEquals("Radiohead", req.artist)
        assertEquals("Pablo Honey", req.album)
        assertEquals(EnrichmentRequest.DEFAULT_TRACK_TYPES, engine.lastTypes)
    }

    @Test fun `trackProfile from SearchCandidate uses candidate MBID`() = runTest {
        val engine = FakeEngine()
        val candidate = SearchCandidate(
            title = "Creep",
            artist = "Radiohead",
            year = null,
            country = null,
            releaseType = null,
            score = 90,
            thumbnailUrl = null,
            identifiers = EnrichmentIdentifiers(musicBrainzId = "track-mbid"),
            provider = "musicbrainz",
        )

        val profile = engine.trackProfile(candidate, album = "Pablo Honey")

        val req = engine.lastRequest as EnrichmentRequest.ForTrack
        assertEquals("track-mbid", req.identifiers.musicBrainzId)
        assertEquals("Pablo Honey", req.album)
    }

    // --- Profile wiring: results flow through to profile fields ---

    @Test fun `profile fields reflect enrich results`() = runTest {
        val engine = FakeEngine()
        engine.resultsToReturn = mapOf(
            EnrichmentType.ARTIST_PHOTO to EnrichmentResult.Success(
                EnrichmentType.ARTIST_PHOTO,
                EnrichmentData.Artwork(url = "https://example.com/photo.jpg"),
                "wikidata", 0.95f,
            ),
            EnrichmentType.ARTIST_BIO to EnrichmentResult.Success(
                EnrichmentType.ARTIST_BIO,
                EnrichmentData.Biography("A band from Oxford", "wikipedia"),
                "wikipedia", 0.9f,
            ),
        )
        engine.identityToReturn = IdentityResolution(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "resolved-mbid"),
            match = IdentityMatch.RESOLVED,
            matchScore = 98,
        )

        val profile = engine.artistProfile("Radiohead")

        assertEquals("https://example.com/photo.jpg", profile.photo?.url)
        assertEquals("A band from Oxford", profile.bio?.text)
        assertEquals(IdentityMatch.RESOLVED, profile.identityMatch)
        assertEquals(98, profile.identityMatchScore)
        assertEquals("resolved-mbid", profile.identifiers.musicBrainzId)
    }

    @Test fun `profile handles empty results gracefully`() = runTest {
        val engine = FakeEngine()

        val profile = engine.artistProfile("Unknown Artist")

        assertNull(profile.photo)
        assertNull(profile.bio)
        assertTrue(profile.genres.isEmpty())
        assertTrue(profile.members.isEmpty())
        assertNull(profile.identityMatch)
    }

    // --- defaultTypesFor ---

    @Test fun `defaultTypesFor returns correct set per request kind`() {
        assertEquals(
            EnrichmentRequest.DEFAULT_ARTIST_TYPES,
            EnrichmentRequest.defaultTypesFor(EnrichmentRequest.forArtist("test")),
        )
        assertEquals(
            EnrichmentRequest.DEFAULT_ALBUM_TYPES,
            EnrichmentRequest.defaultTypesFor(EnrichmentRequest.forAlbum("test", "test")),
        )
        assertEquals(
            EnrichmentRequest.DEFAULT_TRACK_TYPES,
            EnrichmentRequest.defaultTypesFor(EnrichmentRequest.forTrack("test", "test")),
        )
    }

    // --- forceRefresh ---

    @Test fun `artistProfile passes forceRefresh to enrich`() = runTest {
        // Given — a fake engine that records forceRefresh
        val engine = FakeEngine()

        // When — calling artistProfile with forceRefresh=true
        engine.artistProfile("Radiohead", forceRefresh = true)

        // Then — enrich received forceRefresh=true
        assertTrue(engine.lastForceRefresh)
    }

    @Test fun `albumProfile passes forceRefresh to enrich`() = runTest {
        val engine = FakeEngine()
        engine.albumProfile("OK Computer", "Radiohead", forceRefresh = true)
        assertTrue(engine.lastForceRefresh)
    }

    @Test fun `trackProfile passes forceRefresh to enrich`() = runTest {
        val engine = FakeEngine()
        engine.trackProfile("Creep", "Radiohead", forceRefresh = true)
        assertTrue(engine.lastForceRefresh)
    }

    // --- identifiers passthrough ---

    @Test fun `trackProfile passes identifiers with deezerId to request`() = runTest {
        // Given — identifiers with a deezerId from a previous top tracks enrichment
        val engine = FakeEngine()
        val ids = EnrichmentIdentifiers(musicBrainzId = "rec-mbid").withExtra("deezerId", "789")

        // When — calling trackProfile with pre-resolved identifiers
        engine.trackProfile("Karma Police", "Radiohead", identifiers = ids)

        // Then — the request carries both musicBrainzId and deezerId
        val req = engine.lastRequest as EnrichmentRequest.ForTrack
        assertEquals("rec-mbid", req.identifiers.musicBrainzId)
        assertEquals("789", req.identifiers.extra["deezerId"])
    }

    @Test fun `forTrack merges mbid into provided identifiers`() {
        // Given — identifiers with deezerId, plus a separate mbid
        val ids = EnrichmentIdentifiers().withExtra("deezerId", "789")

        // When — creating a request with both mbid and identifiers
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", mbid = "override-mbid", identifiers = ids)

        // Then — mbid is merged into identifiers, deezerId is preserved
        assertEquals("override-mbid", request.identifiers.musicBrainzId)
        assertEquals("789", request.identifiers.extra["deezerId"])
    }

    @Test fun `artistProfile passes identifiers to request`() = runTest {
        val engine = FakeEngine()
        val ids = EnrichmentIdentifiers().withExtra("deezerId", "399")

        engine.artistProfile("Radiohead", identifiers = ids)

        val req = engine.lastRequest as EnrichmentRequest.ForArtist
        assertEquals("399", req.identifiers.extra["deezerId"])
    }

    @Test fun `resolveTrackPreviews returns previews for tracks with deezerId`() = runTest {
        // Given — engine returns preview for requested track
        val engine = FakeEngine()
        engine.resultsToReturn = mapOf(
            EnrichmentType.TRACK_PREVIEW to EnrichmentResult.Success(
                EnrichmentType.TRACK_PREVIEW,
                EnrichmentData.TrackPreview("https://preview.mp3", 30000L, "deezer"),
                "deezer", 0.9f,
            ),
        )

        // When — resolving preview for a track with deezerId
        val results = engine.resolveTrackPreviews(
            listOf(TrackPreviewRequest("Karma Police", "Radiohead",
                identifiers = EnrichmentIdentifiers().withExtra("deezerId", "789"))),
        )

        // Then — preview is returned
        assertEquals(1, results.size)
        assertEquals("Karma Police", results[0].title)
        assertNotNull(results[0].preview)
        assertEquals("https://preview.mp3", results[0].preview!!.url)
    }

    // --- defaultTypesFor ---

    @Test fun `default type sets are composable via set algebra`() {
        // Subtract a type
        val withoutTimeline = EnrichmentRequest.DEFAULT_ARTIST_TYPES - setOf(EnrichmentType.ARTIST_TIMELINE)
        assertTrue(EnrichmentType.GENRE in withoutTimeline)
        assertTrue(EnrichmentType.ARTIST_TIMELINE !in withoutTimeline)

        // Add a type
        val withLyrics = EnrichmentRequest.DEFAULT_ALBUM_TYPES + setOf(EnrichmentType.LYRICS_SYNCED)
        assertTrue(EnrichmentType.LYRICS_SYNCED in withLyrics)
        assertTrue(EnrichmentType.ALBUM_ART in withLyrics)
    }
}
