package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider
import com.landofoz.musicmeta.EnrichmentIdentifiers
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests each provider individually against real APIs.
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true
 *
 * Each test isolates a single provider so failures are easy to diagnose.
 */
class ProviderValidationTest {

    private val f = E2ETestFixture

    @Before
    fun setup() {
        Assume.assumeTrue(
            "E2E tests disabled. Run with -Dinclude.e2e=true",
            System.getProperty("include.e2e") == "true",
        )
    }

    // --- MusicBrainz ---

    @Test
    fun `MusicBrainz - album search returns scored results`() = runTest {
        // Given — MusicBrainz provider with real HTTP client
        val provider = MusicBrainzProvider(f.httpClient, f.mbRateLimiter)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching a well-known album
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — MBID resolved with high confidence and metadata
        assertTrue("Should succeed", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Metadata
        assertNotNull("Should have MBID", success.resolvedIdentifiers?.musicBrainzId)
        assertTrue("Confidence >= 0.80", success.confidence >= 0.80f)
        assertNotNull("Should have genres or label", data.genres ?: data.label)
        println("  Album MBID: ${success.resolvedIdentifiers?.musicBrainzId}, confidence: ${success.confidence}")
        println("  Genres: ${data.genres}, Label: ${data.label}")
    }

    @Test
    fun `MusicBrainz - artist lookup returns relations`() = runTest {
        // Given — MusicBrainz provider with real HTTP client
        val provider = MusicBrainzProvider(f.httpClient, f.mbRateLimiter)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching a well-known artist
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — artist resolved with Wikidata relation
        assertTrue("Should succeed", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertNotNull("Should have Wikidata ID", success.resolvedIdentifiers?.wikidataId)
        println("  Artist MBID: ${success.resolvedIdentifiers?.musicBrainzId}")
        println("  Wikidata: ${success.resolvedIdentifiers?.wikidataId}, Wikipedia: ${success.resolvedIdentifiers?.wikipediaTitle}")
    }

    @Test
    fun `MusicBrainz - direct MBID lookup works`() = runTest {
        // Given — request with a pre-known MBID (Radiohead's artist ID)
        val provider = MusicBrainzProvider(f.httpClient, f.mbRateLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            name = "Radiohead",
        )

        // When — enriching with an existing MBID (direct lookup, no search)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — uses the provided MBID and returns full metadata
        assertTrue("Direct lookup should succeed", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Metadata
        assertEquals("Should use provided MBID", "a74b1b7f-71a5-4011-9441-d0b5e4122711", success.resolvedIdentifiers?.musicBrainzId)
        assertNotNull("Lookup should return wikidataId", success.resolvedIdentifiers?.wikidataId)
        assertNotNull("Lookup should return genres or metadata", data.genres ?: data.country)
        println("  Wikidata: ${success.resolvedIdentifiers?.wikidataId}, Genres: ${data.genres}")
    }

    @Test
    fun `MusicBrainz - handles special chars (AC DC)`() = runTest {
        // Given — "AC/DC" requires Lucene query escaping for the slash
        val provider = MusicBrainzProvider(f.httpClient, f.mbRateLimiter)
        val request = EnrichmentRequest.forArtist("AC/DC")

        // When — enriching an artist with special characters
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — resolves despite the slash in the name
        assertTrue("AC/DC should resolve", result is EnrichmentResult.Success)
        println("  MBID: ${(result as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzId}")
    }

    // --- Cover Art Archive ---

    @Test
    fun `CoverArtArchive - returns art for known release group`() = runTest {
        // Given — CAA provider with a known OK Computer release-group MBID
        val provider = CoverArtArchiveProvider(f.httpClient, f.defaultRateLimiter)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(
                musicBrainzReleaseGroupId = "d1a49970-e498-32dc-ac72-34debff397b4",
            ),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — artwork URL returned (if available in CAA)
        if (result is EnrichmentResult.Success) {
            val artwork = result.data as EnrichmentData.Artwork
            assertTrue("URL should be HTTP", artwork.url.startsWith("http"))
            println("  Artwork: ${artwork.url}")
        } else {
            println("  CAA returned ${result::class.simpleName} (artwork may not be available for this release group)")
        }
    }

    // --- Wikidata ---

    @Test
    fun `Wikidata - returns artist photo via P18`() = runTest {
        // Given — Wikidata provider with Radiohead's Wikidata ID (Q44190)
        val provider = WikidataProvider(f.httpClient, f.defaultRateLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44190"),
            name = "Radiohead",
        )

        // When — enriching for artist photo (looks up P18 image property)
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — Wikimedia Commons photo URL (if P18 exists for this entity)
        if (result is EnrichmentResult.Success) {
            val artwork = result.data as EnrichmentData.Artwork
            assertTrue("URL should be Wikimedia", artwork.url.contains("wikimedia") || artwork.url.contains("commons"))
            println("  Photo: ${artwork.url}")
        } else {
            println("  No P18 image for Radiohead (may vary)")
        }
    }

    // --- Wikipedia ---

    @Test
    fun `Wikipedia - returns bio for direct title`() = runTest {
        // Given — Wikipedia provider with a direct article title
        val provider = WikipediaProvider(f.httpClient, f.defaultRateLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When — enriching for artist biography
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then — substantial biography text with Wikipedia as source
        assertTrue("Should find bio", result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertTrue("Bio should be substantial", bio.text.length > 50)
        assertEquals("Wikipedia", bio.source)
        println("  Bio: ${bio.text.take(100)}...")
    }

    @Test
    fun `Wikipedia - resolves bio via Wikidata sitelinks`() = runTest {
        // Given — Air (French band) with Wikidata ID but no direct Wikipedia title
        val provider = WikipediaProvider(f.httpClient, f.defaultRateLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q318452"),
            name = "Air",
        )

        // When — enriching for artist bio (falls back to Wikidata sitelinks)
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then — biography resolved via the Wikidata→Wikipedia sitelink path
        assertTrue("Should resolve via Wikidata sitelinks", result is EnrichmentResult.Success)
        val bio = (result as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertTrue("Bio should mention French", bio.text.contains("French"))
        println("  Bio: ${bio.text.take(100)}...")
    }

    @Test
    fun `Wikipedia - handles title with special chars`() = runTest {
        // Given — title with parentheses that need URL encoding
        val provider = WikipediaProvider(f.httpClient, f.defaultRateLimiter)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Air (French band)"),
            name = "Air",
        )

        // When — enriching for artist bio
        val result = provider.enrich(request, EnrichmentType.ARTIST_BIO)

        // Then — handles parentheses in title without breaking
        assertTrue("Should handle parentheses in title", result is EnrichmentResult.Success)
    }

    // --- LRCLIB ---

    @Test
    fun `LRCLIB - returns lyrics for well-known track`() = runTest {
        // Given — LRCLIB provider searching for "Creep" by Radiohead
        val provider = LrcLibProvider(f.httpClient, f.lrcLibRateLimiter)
        val request = EnrichmentRequest.forTrack("Creep", "Radiohead", album = "Pablo Honey")

        // When — enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then — lyrics found with at least one format (synced or plain)
        assertTrue("Should find lyrics", result is EnrichmentResult.Success)
        val lyrics = (result as EnrichmentResult.Success).data as EnrichmentData.Lyrics
        assertTrue("Should have some lyrics text",
            lyrics.syncedLyrics?.isNotBlank() == true || lyrics.plainLyrics?.isNotBlank() == true)
        println("  Synced: ${lyrics.syncedLyrics != null}, Plain: ${lyrics.plainLyrics != null}")
    }

    @Test
    fun `LRCLIB - returns NotFound for nonexistent track`() = runTest {
        // Given — LRCLIB provider searching for a gibberish track
        val provider = LrcLibProvider(f.httpClient, f.lrcLibRateLimiter)
        val request = EnrichmentRequest.forTrack("zxqwvnmkjhgf", "NonexistentArtist12345")

        // When — enriching for synced lyrics
        val result = provider.enrich(request, EnrichmentType.LYRICS_SYNCED)

        // Then — NotFound because no such track exists
        assertTrue("Should be NotFound", result is EnrichmentResult.NotFound)
    }

    // --- Deezer ---

    @Test
    fun `Deezer - returns album art via search`() = runTest {
        // Given — Deezer provider with real HTTP client
        val provider = DeezerProvider(f.httpClient, f.defaultRateLimiter)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — artwork URL from Deezer CDN
        assertTrue("Should find art", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue("URL should be Deezer CDN", artwork.url.contains("dzcdn"))
        println("  Art: ${artwork.url}")
    }

    // --- iTunes ---

    @Test
    fun `iTunes - returns album art with upscaled URL`() = runTest {
        // Given — iTunes provider with real HTTP client
        val provider = ITunesProvider(f.httpClient, f.itunesRateLimiter)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — artwork URL upscaled from default to 1200x1200
        assertTrue("Should find art", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue("URL should have 1200x1200", artwork.url.contains("1200x1200"))
        println("  Art: ${artwork.url}")
    }

    companion object {
        private const val USER_AGENT = E2ETestFixture.USER_AGENT
    }
}
