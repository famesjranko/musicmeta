package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testkit.countMatching
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CoverArtArchiveProvider] memoizes `getArtworkMetadata` for the life of one
 * [com.landofoz.musicmeta.engine.ProviderCallScope]: a fan-out over its three metadata-backed types
 * (ALBUM_ART_BACK, ALBUM_BOOKLET, CD_ART), or over ALBUM_ART alongside one of them, fetches the
 * same release's image metadata once for the call, not once per type. A failed fetch is shared on
 * the same terms; a cancelled one is not shared at all.
 */
class CoverArtArchiveMemoTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: CoverArtArchiveProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = CoverArtArchiveProvider(httpClient, RateLimiter(0))
    }

    private fun engine(under: CoverArtArchiveProvider = provider) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(under)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    @Test
    fun `one album's fanout fetches release image metadata once, not once per type`() = runTest {
        // Given - a release whose metadata answers all three artwork-metadata types
        httpClient.givenJsonResponse("release/memo1", METADATA_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo1"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - the three metadata-backed types are enriched in one call
        engine().enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET, EnrichmentType.CD_ART),
        )

        // Then - one request served the whole call, not one per type
        httpClient.assertNoUrlRequestedTwice()
    }

    @Test
    fun `ALBUM_ART and a metadata type share one release image-metadata request`() = runTest {
        // Given - a release whose front-cover redirect resolves independently of the metadata
        // endpoint, and whose metadata also answers ALBUM_ART_BACK
        httpClient.givenRedirectResult(
            "release/memo2/front-",
            HttpResult.Ok("https://archive.org/img/front.jpg"),
        )
        httpClient.givenJsonResponse("release/memo2", METADATA_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo2"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - ALBUM_ART (reached via fetchFrontImage's side-fetch) and a metadata-backed type
        // are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_ART_BACK))

        // Then - the image-metadata endpoint (no /front-* suffix) was hit once, not once per branch
        val metadataRequests = httpClient.requestedUrls.count {
            it.contains("release/memo2") && !it.contains("front-")
        }
        assertEquals(1, metadataRequests)
    }

    @Test
    fun `a release with no image metadata is looked up once, not once per type`() = runTest {
        // Given - a release the metadata endpoint has nothing for (unstubbed resolves to a 404)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "missing1"),
            title = "Unknown Album",
            artist = "Unknown Artist",
        )

        // When - two metadata-backed types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET))

        // Then - the miss was looked up once, not once per type
        assertEquals(1, httpClient.countMatching("release/missing1"))
    }

    @Test
    fun `forceRefresh re-fetches release image metadata, not the previous call's memo`() = runTest {
        // Given - a release whose metadata answers both metadata-backed types, already fetched once
        httpClient.givenJsonResponse("release/memo3", METADATA_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo3"),
            title = "OK Computer",
            artist = "Radiohead",
        )
        val eng = engine()
        eng.enrich(request, setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET))

        // When - the same engine is asked again with forceRefresh, bypassing its cache
        eng.enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET),
            forceRefresh = true,
        )

        // Then - the memo did not survive into the second call: one request per call, two total
        assertEquals(2, httpClient.countMatching("release/memo3"))
    }

    @Test
    fun `a thrown metadata fetch is attempted once for the call, not once per type`() = runTest {
        // Given - a release whose image-metadata endpoint is a transient failure on every attempt
        httpClient.givenRedirectResult(
            "release/memo4/front-",
            HttpResult.Ok("https://archive.org/img/front.jpg"),
        )
        httpClient.givenHttpResult("release/memo4", HttpResult.ServerError(503))
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo4"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - all four types that read that one release document are enriched in one call
        engine().enrich(
            request,
            setOf(
                EnrichmentType.ALBUM_ART,
                EnrichmentType.ALBUM_ART_BACK,
                EnrichmentType.ALBUM_BOOKLET,
                EnrichmentType.CD_ART,
            ),
        )

        // Then - the failing endpoint was attempted once for the call, not once per type
        val metadataRequests = httpClient.requestedUrls.count {
            it.contains("release/memo4") && !it.contains("front-")
        }
        assertEquals(1, metadataRequests)
    }

    @Test
    fun `a timing-out upstream's cancellation is held for the call, not re-attempted per type`() = runTest {
        // Given - a metadata endpoint that reports a hung upstream the way a consumer's own
        // withTimeout does: a CancellationException raised while this caller's job is healthy
        var fetches = 0
        val timingOutClient = object : HttpClient by httpClient {
            override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
                if (!url.contains("release/memo6")) return httpClient.fetchJsonResult(url)
                fetches++
                throw CancellationException("upstream deadline, not ours")
            }
        }
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo6"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - the three metadata-backed types are enriched in one call
        engine(CoverArtArchiveProvider(timingOutClient, RateLimiter(0))).enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET, EnrichmentType.CD_ART),
        )

        // Then - the hung endpoint was attempted once for the call, not once per type
        assertEquals(1, fetches)
    }

    @Test
    fun `a cancelled metadata fetch is not held against a later caller in the same scope`() = runTest {
        // Given - a metadata endpoint that holds its first caller inside the fetch
        httpClient.givenJsonResponse("release/memo5", METADATA_JSON)
        val fetchEntered = CompletableDeferred<Unit>()
        val neverAnswered = CompletableDeferred<Unit>()
        val gatedClient = object : HttpClient by httpClient {
            private var firstCaller = true
            override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
                if (firstCaller && url.contains("release/memo5")) {
                    firstCaller = false
                    fetchEntered.complete(Unit)
                    neverAnswered.await()
                }
                return httpClient.fetchJsonResult(url)
            }
        }
        val gatedProvider = CoverArtArchiveProvider(gatedClient, RateLimiter(0))
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo5"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - the first caller is cancelled mid-fetch and a second asks within the same call scope
        val result = withContext(ProviderCallScope()) {
            val cancelledCaller = async {
                gatedProvider.enrich(request, EnrichmentType.ALBUM_ART_BACK)
            }
            fetchEntered.await()
            cancelledCaller.cancelAndJoin()
            gatedProvider.enrich(request, EnrichmentType.ALBUM_ART_BACK)
        }

        // Then - the second caller fetched afresh instead of inheriting a memoized cancellation
        assertTrue(result is EnrichmentResult.Success)
    }

    private companion object {
        val METADATA_JSON = """
            {
              "images": [
                {"front": false, "types": ["Back"], "image": "https://archive.org/img/back.jpg", "thumbnails": {}},
                {"front": false, "types": ["Booklet"], "image": "https://archive.org/img/booklet.jpg", "thumbnails": {}},
                {"front": false, "types": ["Medium"], "image": "https://archive.org/img/medium.jpg", "thumbnails": {}}
              ]
            }
        """.trimIndent()
    }
}
