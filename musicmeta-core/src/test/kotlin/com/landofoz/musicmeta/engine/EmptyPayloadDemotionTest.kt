package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `Success` must answer the type it claims to answer. Splits into the per-type enumeration
 * ([answers]) and the engine gate that applies it.
 */
class EmptyPayloadDemotionTest {

    // --- The reported case, end to end through the real provider ---

    /**
     * "Paranoid Android" by Radiohead: MusicBrainz matches the recording at score 100 and the
     * recording carries no tags, so the mapper produces an all-null Metadata. Before the gate this
     * surfaced as GENRE Success at confidence 1.0 with nothing in it.
     */
    @Test
    fun `tagless recording at a perfect identity score is NotFound for GENRE`() = runTest {
        // Given — a perfect-score recording match with no tags at all
        val http = FakeHttpClient()
        http.givenJsonResponse("recording?query", TAGLESS_RECORDING_SCORE_100)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When — asking for the one type that empty payload claimed to answer
        val results = engine.enrich(
            EnrichmentRequest.forTrack("Paranoid Android", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )

        // Then — NotFound, not a 100%-confident Success carrying nothing
        assertTrue(
            "expected NotFound, got ${results.raw[EnrichmentType.GENRE]}",
            results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound,
        )
    }

    @Test
    fun `a tagged recording is still a GENRE Success`() = runTest {
        // Given — the same shape, but the recording carries a tag
        val http = FakeHttpClient()
        http.givenJsonResponse("recording?query", TAGGED_RECORDING_SCORE_100)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )

        // When
        val results = engine.enrich(
            EnrichmentRequest.forTrack("Paranoid Android", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )

        // Then — the gate demotes empty payloads only
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(listOf("alternative rock"), (success.data as EnrichmentData.Metadata).genres)
    }

    // --- The engine gate, per affected type ---

    private fun engineWith(vararg results: EnrichmentResult): DefaultEnrichmentEngine {
        val provider = FakeProvider(
            id = "p",
            capabilities = results.map { ProviderCapability(it.typeOf(), 100) },
        ).also { p -> results.forEach { p.givenResult(it.typeOf(), it) } }
        return DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
        )
    }

    private fun EnrichmentResult.typeOf(): EnrichmentType = when (this) {
        is EnrichmentResult.Success -> type
        is EnrichmentResult.NotFound -> type
        is EnrichmentResult.RateLimited -> type
        is EnrichmentResult.Error -> type
    }

    private fun success(type: EnrichmentType, data: EnrichmentData) =
        EnrichmentResult.Success(type, data, "p", 1.0f)

    /**
     * The whole point of the per-type enumeration: one all-null-but-one Metadata answers exactly the
     * type whose field it filled, and no other. LABEL is filled here, so LABEL is the only Success.
     */
    @Test
    fun `a Metadata answers only the type whose field it filled`() = runTest {
        // Given — a payload that answers LABEL and nothing else, offered for four types
        val onlyLabel = EnrichmentData.Metadata(label = "Parlophone")
        val types = listOf(
            EnrichmentType.LABEL, EnrichmentType.GENRE,
            EnrichmentType.RELEASE_DATE, EnrichmentType.COUNTRY,
        )
        val engine = engineWith(*types.map { success(it, onlyLabel) }.toTypedArray())

        // When
        val results = engine.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), types.toSet())

        // Then
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.Success)
        for (type in types - EnrichmentType.LABEL) {
            assertTrue("$type should be demoted", results.raw[type] is EnrichmentResult.NotFound)
        }
    }

    @Test
    fun `an empty list payload is demoted`() = runTest {
        // Given — a provider claiming success with no members
        val engine = engineWith(
            success(EnrichmentType.BAND_MEMBERS, EnrichmentData.BandMembers(emptyList())),
        )

        // When
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.BAND_MEMBERS),
        )

        // Then
        assertTrue(results.raw[EnrichmentType.BAND_MEMBERS] is EnrichmentResult.NotFound)
    }

    @Test
    fun `identity fan-out does not spread a payload to types it cannot answer`() = runTest {
        // Given — identity resolves with a payload that only carries a country
        val identity = EnrichmentResult.Success(
            EnrichmentType.COUNTRY, EnrichmentData.Metadata(country = "GB"), "mb", 1.0f,
            resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1"),
        )
        val provider = FakeProvider(
            id = "mb", isIdentityProvider = true,
            capabilities = listOf(
                ProviderCapability(EnrichmentType.COUNTRY, 100),
                ProviderCapability(EnrichmentType.LABEL, 100),
            ),
        ).also { it.givenIdentityResult(identity) }
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = true),
        )

        // When — asking for the type it answers and one it does not
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.COUNTRY, EnrichmentType.LABEL),
        )

        // Then — COUNTRY inherited from identity, LABEL not handed the same empty answer
        assertTrue(results.raw[EnrichmentType.COUNTRY] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.NotFound)
    }

    // --- The enumeration itself ---

    @Test
    fun `answers enumerates which Metadata field answers which type`() {
        assertTrue(EnrichmentData.Metadata(genres = listOf("rock")).answers(EnrichmentType.GENRE))
        assertTrue(
            EnrichmentData.Metadata(genreTags = listOf(GenreTag("rock", 0.9f)))
                .answers(EnrichmentType.GENRE),
        )
        assertTrue(EnrichmentData.Metadata(label = "XL").answers(EnrichmentType.LABEL))
        assertTrue(EnrichmentData.Metadata(releaseDate = "1997").answers(EnrichmentType.RELEASE_DATE))
        assertTrue(EnrichmentData.Metadata(releaseType = "Album").answers(EnrichmentType.RELEASE_TYPE))
        assertTrue(EnrichmentData.Metadata(country = "GB").answers(EnrichmentType.COUNTRY))
        // ALBUM_METADATA is the grab bag: any field will do, all-null will not
        assertTrue(EnrichmentData.Metadata(barcode = "123").answers(EnrichmentType.ALBUM_METADATA))
        assertFalse(EnrichmentData.Metadata().answers(EnrichmentType.ALBUM_METADATA))
        // An empty list is as unanswered as a null one
        assertFalse(EnrichmentData.Metadata(genres = emptyList()).answers(EnrichmentType.GENRE))
    }

    @Test
    fun `answers rejects blank strings in payloads that cannot be null`() {
        assertFalse(EnrichmentData.Artwork(url = " ").answers(EnrichmentType.ALBUM_ART))
        assertTrue(EnrichmentData.Artwork(url = "https://x/a.jpg").answers(EnrichmentType.ALBUM_ART))
        assertFalse(EnrichmentData.Biography(text = "", source = "wp").answers(EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `an instrumental track answers a lyrics request`() {
        assertTrue(EnrichmentData.Lyrics(isInstrumental = true).answers(EnrichmentType.LYRICS_PLAIN))
        assertFalse(EnrichmentData.Lyrics().answers(EnrichmentType.LYRICS_PLAIN))
    }

    private companion object {
        val TAGLESS_RECORDING_SCORE_100 = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 100,
                "title": "Paranoid Android"
              }]
            }
        """.trimIndent()

        val TAGGED_RECORDING_SCORE_100 = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 100,
                "title": "Paranoid Android",
                "tags": [{"name": "alternative rock", "count": 3}]
              }]
            }
        """.trimIndent()
    }
}
