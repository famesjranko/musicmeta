package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.engine.ResolvedEntityNames
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ITunesPreviewFallbackTest {
    private val type = EnrichmentType.TRACK_PREVIEW
    private val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")
    private val http = FakeHttpClient()
    private val cache = FakeEnrichmentCache()
    private val fixture = checkNotNull(javaClass.getResource("/fixtures/itunes-preview/search-song.json")).readText()
    private val engine = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(DeezerProvider(http, RateLimiter(0)), ITunesProvider(http, RateLimiter(0)))),
        cache, EnrichmentConfig(enableIdentityResolution = false),
    )

    private fun songs(select: (JSONObject) -> Boolean): String {
        val rows = JSONObject(fixture).getJSONArray("results")
        val selected = (0 until rows.length()).map { rows.getJSONObject(it) }.filter(select)
        return JSONObject().put("resultCount", selected.size).put("results", JSONArray(selected)).toString()
    }

    @Test
    fun `Deezer 403 falls through to iTunes`() = runTest {
        // Given - Deezer blocks the host and iTunes returns its real song search response.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        http.givenJsonResponse("itunes.apple.com/search", fixture)

        // When - resolving the preview.
        val result = engine.enrich(request, setOf(type))

        // Then - iTunes supplies the matching clip without negatively caching the block.
        val success = result.raw[type] as EnrichmentResult.Success
        assertEquals("itunes", success.provider)
        assertEquals(JSONObject(fixture).getJSONArray("results").getJSONObject(0).getString("previewUrl"),
            (success.data as EnrichmentData.TrackPreview).url)
        assertEquals("1097861836", success.resolvedIdentifiers?.extra?.get("itunesTrackId"))
        assertTrue(cache.negativeStored.isEmpty())
        engine.close()
    }

    @Test
    fun `an empty Deezer search falls through to iTunes`() = runTest {
        // Given - Deezer has no track and iTunes has a matching preview.
        http.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        http.givenJsonResponse("itunes.apple.com/search", fixture)

        // When - resolving the preview.
        val result = engine.enrich(request, setOf(type))

        // Then - the lower-priority iTunes provider answers.
        assertEquals("itunes", (result.raw[type] as EnrichmentResult.Success).provider)
        assertEquals(1, http.requestedUrls.count { "itunes.apple.com" in it })
        engine.close()
    }

    @Test
    fun `a Deezer preview never calls iTunes`() = runTest {
        // Given - Deezer returns its captured matching preview.
        val deezer = checkNotNull(javaClass.getResource("/fixtures/deezer-preview/search-track.json")).readText()
        http.givenJsonResponse("api.deezer.com", deezer)
        http.givenJsonResponse("itunes.apple.com/search", fixture)

        // When - resolving the preview.
        val result = engine.enrich(request, setOf(type))

        // Then - Deezer wins without making an iTunes request.
        assertEquals("deezer", (result.raw[type] as EnrichmentResult.Success).provider)
        assertFalse(http.requestedUrls.any { "itunes.apple.com" in it })
        engine.close()
    }

    @Test
    fun `covers and different versions cannot supply a preview for the original track`() = runTest {
        // Given - Deezer is empty and iTunes returns only the captured cover and qualified versions.
        http.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        http.givenJsonResponse("itunes.apple.com/search", songs {
            it.getString("artistName") != "Radiohead" || it.getString("trackName") != "Karma Police"
        })

        // When - requesting the original recording.
        val result = engine.enrich(request, setOf(type))

        // Then - the library returns absence rather than playing a cover or another version.
        assertTrue(result.raw[type] is EnrichmentResult.NotFound)
        assertTrue(http.requestedUrls.any { "itunes.apple.com" in it })
        engine.close()
    }

    @Test
    fun `a block followed by an iTunes miss stays an uncached error`() = runTest {
        // Given - Deezer blocks the host and iTunes finds no matching song.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        http.givenJsonResponse("itunes.apple.com/search", """{"resultCount":0,"results":[]}""")

        // When - the same preview is requested twice.
        val first = engine.enrich(request, setOf(type))
        val second = engine.enrich(request, setOf(type))

        // Then - the block remains an error and both requests reach iTunes without negative caching.
        assertEquals(ErrorKind.AUTH, (first.raw[type] as EnrichmentResult.Error).errorKind)
        assertEquals(ErrorKind.AUTH, (second.raw[type] as EnrichmentResult.Error).errorKind)
        assertEquals(2, http.requestedUrls.count { "itunes.apple.com" in it })
        assertTrue(cache.negativeStored.isEmpty())
        engine.close()
    }

    @Test
    fun `a preview stays unavailable and uncached during Deezer breaker cooldown`() = runTest {
        // Given - Deezer blocks the host and iTunes has no matching preview.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        http.givenJsonResponse("itunes.apple.com/search", """{"resultCount":0,"results":[]}""")

        // When - repeated preview requests open Deezer's breaker and continue during cooldown.
        val results = (1..7).map { engine.enrich(request, setOf(type)).raw[type] }

        // Then - cooldown remains an error without negative caching while iTunes is still tried.
        assertTrue(results.all { it is EnrichmentResult.Error })
        assertEquals(ErrorKind.NETWORK, (results.last() as EnrichmentResult.Error).errorKind)
        assertTrue((results.last() as EnrichmentResult.Error).message.contains("cooldown"))
        assertEquals(5, http.requestedUrls.count { "api.deezer.com" in it })
        assertEquals(7, http.requestedUrls.count { "itunes.apple.com" in it })
        assertTrue(cache.negativeStored.isEmpty())
        engine.close()
    }

    @Test
    fun `a preview missing from both catalogs remains negatively cached absence`() = runTest {
        // Given - both providers answer successfully but iTunes song rows have no preview URLs.
        http.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val noMedia = JSONObject(fixture)
        val rows = noMedia.getJSONArray("results")
        repeat(rows.length()) { rows.getJSONObject(it).remove("previewUrl") }
        http.givenJsonResponse("itunes.apple.com/search", noMedia.toString())

        // When - requesting the preview twice.
        val first = engine.enrich(request, setOf(type))
        val second = engine.enrich(request, setOf(type))

        // Then - a genuine miss is cached and is never reported as a transport error.
        assertTrue(first.raw[type] is EnrichmentResult.NotFound)
        assertTrue(second.raw[type] is EnrichmentResult.NotFound)
        assertEquals(1, http.requestedUrls.count { "itunes.apple.com" in it })
        assertEquals(1, cache.negativeStored.size)
        engine.close()
    }

    @Test
    fun `an album hint cannot be replaced by an unrelated release`() = runTest {
        // Given - only the EP release matches the title and artist but the request names OK Computer.
        http.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        http.givenJsonResponse("itunes.apple.com/search", songs { it.getLong("collectionId") == 1112414716L })
        val hinted = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")

        // When - requesting the preview from that album.
        val result = engine.enrich(hinted, setOf(type))

        // Then - the EP is not accepted as a substitute for the requested album.
        assertTrue(result.raw[type] is EnrichmentResult.NotFound)
        assertTrue(http.requestedUrls.any { "itunes.apple.com" in it })
        engine.close()
    }

    @Test
    fun `an iTunes track identifier is looked up and checked against the requested identity`() = runTest {
        // Given - an iTunes track id whose lookup returns the captured cover rather than Radiohead.
        http.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        http.givenJsonResponse("itunes.apple.com/lookup", songs { it.getString("artistName") == "Rockabye Baby!" })
        val identified = EnrichmentRequest.forTrack("Karma Police", "Radiohead",
            identifiers = EnrichmentIdentifiers(extra = mapOf("itunesTrackId" to "982317332")))

        // When - requesting the identified preview.
        val result = engine.enrich(identified, setOf(type))

        // Then - a wrong identifier cannot make a cover play or trigger a name-search substitution.
        assertTrue(result.raw[type] is EnrichmentResult.NotFound)
        assertTrue(http.requestedUrls.any { "lookup?id=982317332" in it })
        assertFalse(http.requestedUrls.any { "itunes.apple.com/search" in it })
        engine.close()
    }

    @Test
    fun `an alias pool cannot turn a cover into the requested artist`() = runTest {
        // Given - identity names Radiohead while the song pool contains only a real cover.
        val names = ResolvedEntityNames(this)
        names.offerAliases { listOf(AlternativeName("Radiohead", official = true)) }
        http.givenJsonResponse("itunes.apple.com/search", songs { it.getString("artistName") == "Rockabye Baby!" })
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - selecting a preview with the resolved alias pool available.
        val result = withContext(names) { provider.enrich(request, type) }

        // Then - a pool containing the requested name does not corroborate the cover artist.
        assertTrue(result is EnrichmentResult.NotFound)
        engine.close()
    }

    @Test
    fun `a candidate published under a resolved alias can supply a preview`() = runTest {
        // Given - the request uses an earlier band name and identity supplies its current name.
        val names = ResolvedEntityNames(this)
        names.offerAliases { listOf(AlternativeName("Radiohead", official = true)) }
        http.givenJsonResponse("itunes.apple.com/search", fixture)
        val provider = ITunesProvider(http, RateLimiter(0))
        val aliased = EnrichmentRequest.forTrack("Karma Police", "On A Friday")

        // When - selecting a preview through that alias pool.
        val result = withContext(names) { provider.enrich(aliased, type) }

        // Then - the actual candidate name is corroborated and its confidence reflects the alias tier.
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(0.76f, (result as EnrichmentResult.Success).confidence, 0.001f)
        engine.close()
    }

    @Test
    fun `a cached iTunes URL is reused without fetching preview media`() = runTest {
        // Given - a successful fallback after a Deezer block is cached as URL metadata.
        http.givenHttpResult("api.deezer.com", HttpResult.ClientError(403))
        http.givenJsonResponse("itunes.apple.com/search", fixture)
        val first = engine.enrich(request, setOf(type))

        // When - the same preview is served again from cache.
        val cached = engine.enrich(request, setOf(type))

        // Then - the cached metadata answers without downloading or proxying preview media.
        assertEquals(first.raw[type], cached.raw[type])
        assertEquals(2, http.requestedUrls.size)
        assertTrue(http.requestedUrls.all { "api.deezer.com" in it || "itunes.apple.com/search" in it })
        engine.close()
    }

    @Test
    fun `a malformed native preview identifier prevents any iTunes request`() = runTest {
        // Given - a malformed explicit track id rather than a name-only request.
        val invalid = EnrichmentRequest.forTrack("Karma Police", "Radiohead",
            identifiers = EnrichmentIdentifiers(extra = mapOf("itunesTrackId" to "not-a-track-id")))
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - resolving the preview directly.
        val result = provider.enrich(invalid, type)

        // Then - the invalid identifier is refused without a search that could hide the mistake.
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(http.requestedUrls.isEmpty())
        engine.close()
    }
}
