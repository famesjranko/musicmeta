package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * E2E test verifying OkHttpEnrichmentClient works with the real engine
 * and real APIs. Gated by -Dinclude.e2e=true.
 *
 * Run: ./gradlew :musicmeta-okhttp:test -Dinclude.e2e=true
 */
class OkHttpE2ETest {

    private lateinit var engine: EnrichmentEngine

    @Before
    fun setup() {
        Assume.assumeTrue(
            "E2E tests disabled. Run with -Dinclude.e2e=true",
            System.getProperty("include.e2e") == "true",
        )

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .build()
        val adapter = OkHttpEnrichmentClient(
            okHttpClient,
            "MusicMetaTest/1.0 (https://github.com/famesjranko/musicmeta)",
        )
        engine = EnrichmentEngine.Builder()
            .httpClient(adapter)
            .withDefaultProviders()
            .build()
    }

    @Test
    fun `OkHttp engine resolves Radiohead artist with genre and photo`() = runBlocking {
        // Given — real API request via OkHttp adapter
        val request = EnrichmentRequest.forArtist("Radiohead")
        val types = setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO)

        // When — enriching through OkHttp transport
        val results = engine.enrich(request, types)

        // Then — at least genre should resolve (MusicBrainz + free providers)
        val genre = results.raw[EnrichmentType.GENRE]
        assertTrue(
            "GENRE should be Success via OkHttp adapter, got $genre",
            genre is EnrichmentResult.Success,
        )
        println("  GENRE: ${(genre as EnrichmentResult.Success).provider}, conf=${genre.confidence}")
    }

    @Test
    fun `OkHttp engine resolves OK Computer album art`() = runBlocking {
        // Given — album art request (Cover Art Archive returns 307 redirect)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val types = setOf(EnrichmentType.ALBUM_ART)

        // When — enriching through OkHttp transport
        val results = engine.enrich(request, types)

        // Then — Cover Art Archive should resolve album art via redirect
        val art = results.raw[EnrichmentType.ALBUM_ART]
        assertTrue(
            "ALBUM_ART should be Success via OkHttp adapter, got $art",
            art is EnrichmentResult.Success,
        )
        val artwork = (art as EnrichmentResult.Success).data as com.landofoz.musicmeta.EnrichmentData.Artwork
        assertTrue("Artwork URL should not be empty", artwork.url.isNotEmpty())
        println("  ALBUM_ART: ${art.provider}, url=${artwork.url.take(80)}")
    }

    @Test
    fun `OkHttp engine handles enrichBatch with Flow`() = runBlocking {
        // Given — multiple album requests via OkHttp transport
        val requests = listOf(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            EnrichmentRequest.forAlbum("Kid A", "Radiohead"),
        )

        // When — batch enriching via Flow
        var count = 0
        engine.enrichBatch(requests, setOf(EnrichmentType.GENRE)).collect { (request, results) ->
            count++
            val genre = results.raw[EnrichmentType.GENRE]
            val label = (request as? EnrichmentRequest.ForAlbum)?.title ?: "?"
            println("  [$count] $label: ${genre?.javaClass?.simpleName}")
        }

        // Then — both requests emitted
        assertEquals("Both requests should emit results", 2, count)
    }
}
