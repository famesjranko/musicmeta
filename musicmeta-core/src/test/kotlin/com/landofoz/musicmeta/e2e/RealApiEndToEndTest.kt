package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
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
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * End-to-end tests against real APIs.
 * Run manually: ./gradlew :enrichment-core:test -Dinclude.e2e=true
 *
 * These tests use well-known, stable music data so results are deterministic.
 * Rate limits are respected via per-provider rate limiters.
 */
class RealApiEndToEndTest {

    private lateinit var engine: EnrichmentEngine

    @Before
    fun setup() {
        // Skip unless explicitly enabled
        Assume.assumeTrue(
            "E2E tests disabled. Run with -Dinclude.e2e=true",
            System.getProperty("include.e2e") == "true",
        )

        val httpClient = DefaultHttpClient(USER_AGENT)
        engine = EnrichmentEngine.Builder()
            .addProvider(MusicBrainzProvider(httpClient, RateLimiter(1100)))
            .addProvider(CoverArtArchiveProvider(httpClient, RateLimiter(100)))
            .addProvider(WikidataProvider(httpClient, RateLimiter(100)))
            .addProvider(WikipediaProvider(httpClient, RateLimiter(100)))
            .addProvider(LrcLibProvider(httpClient, RateLimiter(200)))
            .addProvider(DeezerProvider(httpClient, RateLimiter(100)))
            .addProvider(ITunesProvider(httpClient, RateLimiter(3000)))
            .build()
    }

    // --- MusicBrainz Identity Resolution ---

    @Test
    fun `MusicBrainz resolves OK Computer by Radiohead`() = runTest {
        // Given — a well-known album request
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for genre (triggers MusicBrainz identity resolution)
        val result = engine.enrich(request, setOf(EnrichmentType.GENRE))

        // Then — MBID resolved with high confidence
        val resolution = extractResolution(result)
        assertNotNull("Should resolve MBID", resolution)
        assertNotNull("Should have MBID", resolution!!.musicBrainzId)
        assertTrue("Score should be >= 80", resolution.score >= 80)
        println("  MBID: ${resolution.musicBrainzId}, score: ${resolution.score}")
        println("  Genres: ${resolution.metadata?.genres}")
        println("  Label: ${resolution.metadata?.label}")
    }

    @Test
    fun `MusicBrainz resolves Radiohead artist`() = runTest {
        // Given — a well-known artist request
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for genre (triggers MusicBrainz identity resolution)
        val result = engine.enrich(request, setOf(EnrichmentType.GENRE))

        // Then — artist MBID resolved with Wikidata link
        val resolution = extractResolution(result)
        assertNotNull("Should resolve artist MBID", resolution)
        assertNotNull("Should have wikidataId", resolution!!.wikidataId)
        println("  MBID: ${resolution.musicBrainzId}")
        println("  WikidataId: ${resolution.wikidataId}")
        println("  WikipediaTitle: ${resolution.wikipediaTitle}")
    }

    @Test
    fun `MusicBrainz resolves Dark Side of the Moon by Pink Floyd`() = runTest {
        // Given — another well-known album for cross-validation
        val request = EnrichmentRequest.forAlbum("The Dark Side of the Moon", "Pink Floyd")

        // When — enriching for genre and label
        val result = engine.enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.LABEL))

        // Then — high-confidence match with metadata
        val resolution = extractResolution(result)
        assertNotNull("Should resolve", resolution)
        assertTrue("Score should be high", resolution!!.score >= 90)
        println("  Label: ${resolution.metadata?.label}")
        println("  Date: ${resolution.metadata?.releaseDate}")
    }

    // --- Cover Art Archive ---

    @Test
    fun `Cover Art Archive returns artwork for OK Computer`() = runTest {
        // Given — a well-known album with cover art in CAA
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art (MusicBrainz resolves MBID, then CAA fetches art)
        val result = engine.enrich(request, setOf(EnrichmentType.ALBUM_ART))

        // Then — artwork URL returned via HTTP
        val art = result[EnrichmentType.ALBUM_ART]
        assertTrue("Should find album art", art is EnrichmentResult.Success)
        val artwork = (art as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue("URL should be HTTP", artwork.url.startsWith("http"))
        println("  Artwork URL: ${artwork.url}")
        println("  Provider: ${art.provider}")
    }

    // --- Wikidata Artist Photos ---

    @Test
    fun `Wikidata returns artist photo for Radiohead`() = runTest {
        // Given — a well-known artist that may have a P18 image on Wikidata
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for artist photo
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_PHOTO))

        // Then — photo URL from Wikimedia Commons (if P18 exists)
        val photo = result[EnrichmentType.ARTIST_PHOTO]
        if (photo is EnrichmentResult.Success) {
            val artwork = photo.data as EnrichmentData.Artwork
            assertTrue("URL should contain wikimedia", artwork.url.contains("wikimedia") || artwork.url.contains("commons"))
            println("  Photo URL: ${artwork.url}")
        } else {
            println("  No photo found (Wikidata may not have P18 for Radiohead)")
        }
    }

    // --- Wikipedia Bios ---

    @Test
    fun `Wikipedia returns biography for Radiohead`() = runTest {
        // Given — a well-known English band with a Wikipedia article
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for artist biography
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_BIO))

        // Then — biography text mentioning "English" with Wikipedia as source
        val bio = result[EnrichmentType.ARTIST_BIO]
        assertTrue("Should find biography", bio is EnrichmentResult.Success)
        val biography = (bio as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertTrue("Bio should mention English", biography.text.contains("English"))
        assertEquals("Source should be Wikipedia", "Wikipedia", biography.source)
        println("  Bio: ${biography.text.take(100)}...")
    }

    @Test
    fun `Wikipedia resolves bio via Wikidata sitelinks for Air`() = runTest {
        // Given — "Air" has Wikidata but no direct Wikipedia relation in MusicBrainz
        val request = EnrichmentRequest.forArtist("Air")

        // When — enriching for artist biography (resolves via Wikidata sitelinks)
        val result = engine.enrich(request, setOf(EnrichmentType.ARTIST_BIO))

        // Then — biography found via the Wikidata→Wikipedia sitelink path
        val bio = result[EnrichmentType.ARTIST_BIO]
        assertTrue("Should find Air biography via Wikidata sitelinks", bio is EnrichmentResult.Success)
        val biography = (bio as EnrichmentResult.Success).data as EnrichmentData.Biography
        assertTrue("Bio should mention French", biography.text.contains("French"))
        println("  Bio: ${biography.text.take(100)}...")
    }

    // --- LRCLIB Lyrics ---

    @Test
    fun `LRCLIB returns synced lyrics for Creep by Radiohead`() = runTest {
        // Given — a well-known track with lyrics in LRCLIB
        val request = EnrichmentRequest.forTrack("Creep", "Radiohead", album = "Pablo Honey")

        // When — enriching for both synced and plain lyrics
        val result = engine.enrich(
            request,
            setOf(EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN),
        )

        // Then — at least one lyrics format returned with non-blank text
        val synced = result[EnrichmentType.LYRICS_SYNCED]
        val plain = result[EnrichmentType.LYRICS_PLAIN]
        val success = synced as? EnrichmentResult.Success ?: plain as? EnrichmentResult.Success
        assertNotNull("Should find lyrics for Creep", success)
        val lyrics = success!!.data as EnrichmentData.Lyrics
        val text = lyrics.syncedLyrics ?: lyrics.plainLyrics ?: ""
        assertTrue("Lyrics should contain recognizable text", text.isNotBlank())
        println("  Has synced: ${lyrics.syncedLyrics != null}")
        println("  Has plain: ${lyrics.plainLyrics != null}")
        println("  First line: ${text.lines().first().take(60)}")
    }

    @Test
    fun `LRCLIB detects instrumental track`() = runTest {
        // Given — "Treefingers" by Radiohead is an instrumental track
        val request = EnrichmentRequest.forTrack("Treefingers", "Radiohead", album = "Kid A")

        // When — enriching for lyrics
        val result = engine.enrich(
            request,
            setOf(EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN),
        )

        // Then — either marked as instrumental or not found in LRCLIB
        val success = result.values.filterIsInstance<EnrichmentResult.Success>()
            .firstOrNull { it.data is EnrichmentData.Lyrics }
        if (success != null) {
            val lyrics = success.data as EnrichmentData.Lyrics
            println("  Instrumental: ${lyrics.isInstrumental}")
            println("  Has text: ${lyrics.plainLyrics?.isNotBlank() == true}")
        } else {
            println("  Track not found in LRCLIB (may not have entry)")
        }
    }

    // --- Deezer Fallback ---

    @Test
    fun `Deezer returns album art as fallback`() = runTest {
        // Given — engine with only Deezer (no MusicBrainz/CAA) to isolate fallback
        val httpClient = DefaultHttpClient(USER_AGENT)
        val deezerOnly = EnrichmentEngine.Builder()
            .addProvider(DeezerProvider(httpClient, RateLimiter(100)))
            .build()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art through Deezer only
        val result = deezerOnly.enrich(request, setOf(EnrichmentType.ALBUM_ART))

        // Then — artwork URL from Deezer CDN
        val art = result[EnrichmentType.ALBUM_ART]
        assertTrue("Deezer should find album art", art is EnrichmentResult.Success)
        val artwork = (art as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue("URL should be Deezer CDN", artwork.url.contains("dzcdn"))
        println("  Deezer artwork: ${artwork.url}")
    }

    // --- iTunes Fallback ---

    @Test
    fun `iTunes returns album art as fallback`() = runTest {
        // Given — engine with only iTunes to isolate fallback
        val httpClient = DefaultHttpClient(USER_AGENT)
        val itunesOnly = EnrichmentEngine.Builder()
            .addProvider(ITunesProvider(httpClient, RateLimiter(3000)))
            .build()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art through iTunes only
        val result = itunesOnly.enrich(request, setOf(EnrichmentType.ALBUM_ART))

        // Then — artwork URL upscaled to 1200x1200
        val art = result[EnrichmentType.ALBUM_ART]
        assertTrue("iTunes should find album art", art is EnrichmentResult.Success)
        val artwork = (art as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue("URL should be upscaled to 1200", artwork.url.contains("1200x1200"))
        println("  iTunes artwork: ${artwork.url}")
    }

    // --- Full Pipeline ---

    @Test
    fun `full album enrichment pipeline for OK Computer`() = runTest {
        // Given — all enrichment types requested for a well-known album
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val types = setOf(
            EnrichmentType.ALBUM_ART,
            EnrichmentType.GENRE,
            EnrichmentType.LABEL,
            EnrichmentType.RELEASE_DATE,
            EnrichmentType.RELEASE_TYPE,
            EnrichmentType.COUNTRY,
        )

        // When — full pipeline: MusicBrainz → CAA → metadata extraction
        val result = engine.enrich(request, types)

        // Then — identity resolved, artwork found, and metadata populated
        println("  Results:")
        result.forEach { (type, res) ->
            val status = when (res) {
                is EnrichmentResult.Success -> "SUCCESS (${res.provider}, conf=${res.confidence})"
                is EnrichmentResult.NotFound -> "NOT_FOUND (${res.provider})"
                is EnrichmentResult.RateLimited -> "RATE_LIMITED"
                is EnrichmentResult.Error -> "ERROR: ${(res as EnrichmentResult.Error).message}"
            }
            println("    $type: $status")
        }

        val resolution = extractResolution(result)
        assertNotNull("Should have identity resolution", resolution)
        println("  MBID: ${resolution!!.musicBrainzId}")
        println("  Genres: ${resolution.metadata?.genres}")
        println("  Label: ${resolution.metadata?.label}")
        println("  Date: ${resolution.metadata?.releaseDate}")
        println("  Type: ${resolution.metadata?.releaseType}")
        println("  Country: ${resolution.metadata?.country}")

        val art = result[EnrichmentType.ALBUM_ART]
        assertTrue("Should have artwork", art is EnrichmentResult.Success)
    }

    @Test
    fun `full artist enrichment pipeline for Pink Floyd`() = runTest {
        // Given — artist enrichment requesting photo, bio, and genre
        val request = EnrichmentRequest.forArtist("Pink Floyd")
        val types = setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BIO, EnrichmentType.GENRE)

        // When — full pipeline: MusicBrainz → Wikidata → Wikipedia
        val result = engine.enrich(request, types)

        // Then — biography found with substantial text
        println("  Results:")
        result.forEach { (type, res) ->
            val status = when (res) {
                is EnrichmentResult.Success -> "SUCCESS (${res.provider})"
                is EnrichmentResult.NotFound -> "NOT_FOUND (${res.provider})"
                is EnrichmentResult.RateLimited -> "RATE_LIMITED"
                is EnrichmentResult.Error -> "ERROR: ${(res as EnrichmentResult.Error).message}"
            }
            println("    $type: $status")
        }

        val bio = result[EnrichmentType.ARTIST_BIO]
        assertTrue("Should have biography", bio is EnrichmentResult.Success)
        val biography = (bio as EnrichmentResult.Success).data as EnrichmentData.Biography
        println("  Bio: ${biography.text.take(120)}...")
    }

    // --- Search ---

    @Test
    fun `search returns multiple album candidates`() = runTest {
        // Given — a partial album name to test fuzzy matching
        val request = EnrichmentRequest.forAlbum("Dark Side", "Pink Floyd")

        // When — searching for album candidates
        val candidates = engine.search(request, limit = 5)

        // Then — multiple candidates returned
        assertTrue("Should return candidates", candidates.isNotEmpty())
        println("  Candidates:")
        candidates.forEach { c ->
            println("    ${c.title} by ${c.artist} (${c.year}) — score ${c.score}, thumb=${c.thumbnailUrl != null}")
        }
    }

    @Test
    fun `search returns multiple artist candidates`() = runTest {
        // Given — a common artist name with multiple matches (band vs. solo vs. tribute)
        val request = EnrichmentRequest.forArtist("Air")

        // When — searching for artist candidates
        val candidates = engine.search(request, limit = 5)

        // Then — multiple candidates returned
        assertTrue("Should return candidates", candidates.isNotEmpty())
        println("  Candidates:")
        candidates.forEach { c ->
            println("    ${c.title} (${c.country}, ${c.releaseType}) — score ${c.score}")
        }
    }

    // --- Edge Cases ---

    @Test
    fun `handles obscure album gracefully`() = runTest {
        // Given — a completely nonexistent album/artist combination
        val request = EnrichmentRequest.forAlbum("zxqwvnmkjhgf", "NonexistentArtist12345")

        // When — enriching for art and genre
        val result = engine.enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE),
        )

        // Then — does not crash, returns NotFound results
        println("  Results for nonexistent album:")
        result.forEach { (type, res) ->
            println("    $type: ${res::class.simpleName}")
        }
        assertTrue("Should handle gracefully", result.isNotEmpty())
    }

    @Test
    fun `handles special characters in search`() = runTest {
        // Given — "AC/DC" has a slash that needs Lucene escaping
        val request = EnrichmentRequest.forArtist("AC/DC")

        // When — enriching for genre
        val result = engine.enrich(request, setOf(EnrichmentType.GENRE))

        // Then — resolves despite the special character
        val resolution = extractResolution(result)
        println("  AC/DC: MBID=${resolution?.musicBrainzId}, score=${resolution?.score}")
        assertNotNull("Should resolve AC/DC", resolution)
    }

    private fun extractResolution(
        result: Map<EnrichmentType, EnrichmentResult>,
    ): EnrichmentData.IdentifierResolution? =
        result.values.filterIsInstance<EnrichmentResult.Success>()
            .mapNotNull { it.data as? EnrichmentData.IdentifierResolution }
            .firstOrNull()

    companion object {
        private const val USER_AGENT =
            "Cascade/1.0-test (Android music player; https://github.com/famesjranko/cascade)"
    }
}
