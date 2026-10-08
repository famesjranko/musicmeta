package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What an `ARTIST_PHOTO` result carries from the selected file's `imageinfo`, and what it does not
 * depend on. The photo is chosen from the article's media list before `imageinfo` is asked, so no
 * answer to that second request, however odd, restrictive or absent, changes the photo returned:
 * it changes only which facts ride beside it.
 */
class WikipediaPhotoAttributionTest {

    private class RecordingLogger : EnrichmentLogger {
        val debugMessages: MutableList<String> = CopyOnWriteArrayList()
        override fun debug(tag: String, message: String) {
            debugMessages.add(message)
        }
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
    }

    private val logger = RecordingLogger()

    private fun photoProvider(http: HttpClient) = WikipediaProvider(http, RateLimiter(0L), logger = logger)

    private fun radioheadHttp(imageInfo: String? = null): FakeHttpClient = FakeHttpClient().also {
        it.givenJsonResponse("page/media-list", UpstreamPools.body(POOL, "radiohead-media-list.json"))
        if (imageInfo != null) it.givenJsonResponse("prop=imageinfo", imageInfo)
    }

    private fun capture(file: String) = UpstreamPools.body(POOL, file)

    private val request = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
        name = "Radiohead",
    )

    private suspend fun photoOf(http: HttpClient): EnrichmentData.Artwork {
        val result = photoProvider(http).enrich(request, EnrichmentType.ARTIST_PHOTO)
        assertTrue("expected Success but was $result", result is EnrichmentResult.Success)
        return (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
    }

    @Test
    fun `the photo carries the selected file's own creator and licence`() = runTest {
        // Given - the Radiohead media list and the live imageinfo answer for its lead image
        val http = radioheadHttp(capture("imageinfo-radiohead-lead.json"))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the facts are the file's own: Raph_PH under CC BY 4.0, never the article's CC BY-SA 4.0
        assertEquals("Raph_PH", artwork.attribution?.creator)
        assertEquals("CC BY 4.0", artwork.attribution?.licence)
        assertEquals("https://creativecommons.org/licenses/by/4.0", artwork.attribution?.licenceUrl)
        assertEquals(
            "https://commons.wikimedia.org/wiki/File:RadioheadO2211125_composite.jpg",
            artwork.attribution?.sourceUrl,
        )
    }

    @Test
    fun `imageinfo is asked once, for the selected file only`() = runTest {
        // Given - the Radiohead media list, whose lead image is one of several files
        val http = radioheadHttp(capture("imageinfo-radiohead-lead.json"))

        // When - enriching for artist photo
        photoOf(http)

        // Then - exactly one imageinfo request goes out, naming the lead image
        val imageInfoRequests = http.requestedUrls.filter { it.contains("prop=imageinfo") }
        assertEquals(1, imageInfoRequests.size)
        assertTrue(imageInfoRequests.single().contains("titles=File%3ARadioheadO2211125_composite.jpg"))
    }

    @Test
    fun `the selected image and its confidence do not depend on the imageinfo answer`() = runTest {
        // Given - one run whose imageinfo is the lead's own answer and one whose imageinfo says non-free
        val own = radioheadHttp(capture("imageinfo-radiohead-lead.json"))
        val nonFree = radioheadHttp(capture("imageinfo-non-free.json"))

        // When - enriching for artist photo against each
        val ownResult = photoProvider(own).enrich(request, EnrichmentType.ARTIST_PHOTO)
        val nonFreeResult = photoProvider(nonFree).enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - the same image, sizes and the documented 0.6 confidence come back from both
        val ownSuccess = ownResult as EnrichmentResult.Success
        val nonFreeSuccess = nonFreeResult as EnrichmentResult.Success
        assertEquals(0.6f, ownSuccess.confidence)
        assertEquals(ownSuccess.confidence, nonFreeSuccess.confidence)
        assertEquals((ownSuccess.data as EnrichmentData.Artwork).url, (nonFreeSuccess.data as EnrichmentData.Artwork).url)
        assertEquals(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a1/RadioheadO2211125_composite.jpg" +
                "/1280px-RadioheadO2211125_composite.jpg",
            (ownSuccess.data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `the ARTIST_PHOTO capability stays at priority 30 on a Wikipedia title`() {
        // Given - a provider built over any client
        val provider = photoProvider(FakeHttpClient())

        // When - its capabilities are read
        val photo = provider.capabilities.single { it.type == EnrichmentType.ARTIST_PHOTO }

        // Then - the capability is declared, at priority 30, needing a Wikipedia title
        assertEquals(30, photo.priority)
        assertEquals(IdentifierRequirement.WIKIPEDIA_TITLE, photo.identifierRequirement)
    }

    @Test
    fun `a file the wiki does not know still returns the photo, with no attribution`() = runTest {
        // Given - the media list and the live imageinfo answer for a title that is not a file
        val http = radioheadHttp(capture("imageinfo-missing-file.json"))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned and carries no attribution
        assertTrue(artwork.url.contains("RadioheadO2211125_composite.jpg"))
        assertNull(artwork.attribution)
    }

    @Test
    fun `an answer with no extmetadata returns the photo carrying the file and its page`() = runTest {
        // Given - a derived copy of the lead image's answer with extmetadata removed
        val body = JSONObject(capture("imageinfo-radiohead-lead.json"))
        body.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
            .getJSONArray("imageinfo").getJSONObject(0).remove("extmetadata")
        val http = radioheadHttp(body.toString())

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned with the facts that were read, which are the title and page
        assertEquals(
            Attribution(
                title = "File:RadioheadO2211125 composite.jpg",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:RadioheadO2211125_composite.jpg",
            ),
            artwork.attribution,
        )
    }

    @Test
    fun `a non-free file returns the photo carrying its fair use facts`() = runTest {
        // Given - the live imageinfo answer for a file uploaded under fair use, with NonFree true
        val http = radioheadHttp(capture("imageinfo-non-free.json"))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned and the facts, including the non-free restriction, ride beside it
        assertEquals("Fair use", artwork.attribution?.licence)
        assertEquals(listOf("non-free"), artwork.attribution?.restrictions)
    }

    @Test
    fun `a file with a reuse restriction returns the photo carrying the restriction`() = runTest {
        // Given - the live imageinfo answer for a file with Restrictions of personality
        val http = radioheadHttp(capture("imageinfo-personality-restricted.json"))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned with the restriction listed beside its licence
        assertEquals(listOf("personality"), artwork.attribution?.restrictions)
        assertEquals("CC BY-SA 3.0", artwork.attribution?.licence)
    }

    @Test
    fun `a file with an http licence link returns the photo without the link and with the rest`() = runTest {
        // Given - the live imageinfo answer for a file whose LicenseUrl is http
        val http = radioheadHttp(capture("imageinfo-multi-licensed.json"))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned, the link is absent, and the licence name and creator remain
        assertNull(artwork.attribution?.licenceUrl)
        assertEquals("GFDL 1.2", artwork.attribution?.licence)
        assertEquals("Ralf Roletschek", artwork.attribution?.creator)
    }

    @Test
    fun `malformed imageinfo JSON returns the photo with no attribution`() = runTest {
        // Given - an imageinfo answer that is not JSON
        val http = radioheadHttp("<html>not json</html>")

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned and carries no attribution
        assertNull(artwork.attribution)
    }

    @Test
    fun `an imageinfo 404 returns the photo with no attribution and logs the status`() = runTest {
        // Given - the media list answers and the imageinfo request is refused with a 404
        val http = radioheadHttp()
        http.givenHttpResult("prop=imageinfo", HttpResult.ClientError(404))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned without attribution, and the debug log names the 404
        assertNull(artwork.attribution)
        assertTrue(logger.debugMessages.any { it.contains("404") })
    }

    @Test
    fun `an imageinfo 500 returns the photo with no attribution and logs the status`() = runTest {
        // Given - the media list answers and the imageinfo request fails with a 500
        val http = radioheadHttp()
        http.givenHttpResult("prop=imageinfo", HttpResult.ServerError(500))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned without attribution, and the debug log names the 500
        assertNull(artwork.attribution)
        assertTrue(logger.debugMessages.any { it.contains("500") })
    }

    @Test
    fun `an imageinfo 429 returns the photo with no attribution`() = runTest {
        // Given - the media list answers and the imageinfo request is rate limited
        val http = radioheadHttp()
        http.givenHttpResult("prop=imageinfo", HttpResult.RateLimited(retryAfterMs = 1_000))

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned and carries no attribution
        assertNull(artwork.attribution)
    }

    @Test
    fun `an imageinfo IOException returns the photo with no attribution`() = runTest {
        // Given - the media list answers and the imageinfo request throws an IOException
        val http = radioheadHttp()
        http.givenIoException("prop=imageinfo")

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned and carries no attribution
        assertNull(artwork.attribution)
    }

    @Test
    fun `an Action API error inside a 200 returns the photo and logs the upstream error code`() = runTest {
        // Given - the media list answers and imageinfo is shed with maxlag inside a 200
        val http = radioheadHttp(MAXLAG_ERROR_JSON)

        // When - enriching for artist photo
        val artwork = photoOf(http)

        // Then - the photo is returned without attribution, and the debug log carries the code
        assertNull(artwork.attribution)
        assertTrue(logger.debugMessages.any { it.contains("maxlag") })
    }

    @Test
    fun `cancelling the job during the imageinfo call cancels the enrichment and returns no photo`() = runTest {
        // Given - an imageinfo call that suspends until cancelled, after the media list answered
        val inImageInfo = CompletableDeferred<Unit>()
        val mediaListOnly = radioheadHttp()
        val http = object : HttpClient by mediaListOnly {
            override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
                if (!url.contains("prop=imageinfo")) return mediaListOnly.fetchJsonResult(url)
                inImageInfo.complete(Unit)
                awaitCancellation()
            }
        }
        var result: EnrichmentResult? = null
        val job = launch { result = photoProvider(http).enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // When - the job is cancelled while imageinfo is in flight
        inImageInfo.await()
        job.cancel()
        job.join()

        // Then - the cancellation propagated: no result, not a photo without attribution
        assertTrue(job.isCancelled)
        assertNull(result)
    }

    @Test
    fun `a media list failure is still classified as an Error and imageinfo is not asked`() = runTest {
        // Given - the media list request throws an IOException
        val http = FakeHttpClient()
        http.givenIoException("page/media-list")

        // When - enriching for artist photo
        val result = photoProvider(http).enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - the result is a NETWORK Error and no imageinfo request was made
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertTrue(http.requestedUrls.none { it.contains("prop=imageinfo") })
    }

    @Test
    fun `a page extract Action API error keeps its code in the Error message`() = runTest {
        // Given - the page extract is shed with maxlag inside a 200
        val http = FakeHttpClient()
        http.givenJsonResponse("prop=extracts", MAXLAG_ERROR_JSON)

        // When - enriching for artist bio
        val result = photoProvider(http).enrich(request, EnrichmentType.ARTIST_BIO)

        // Then - the Error message names the upstream code
        val error = result as EnrichmentResult.Error
        assertTrue(error.message, error.message.contains("maxlag"))
        assertNotNull(error.cause)
    }

    private companion object {
        const val POOL = "wikipedia-file-attribution"

        // Same shape as the 2026-08-12 capture in WikipediaProviderTest: the Action API's own
        // failure, served with HTTP 200.
        val MAXLAG_ERROR_JSON = """{
            "error": {
                "code": "maxlag",
                "info": "Waiting for 10.64.48.169: 0.385659 seconds lagged.",
                "host": "10.64.48.169",
                "lag": 0.385659,
                "type": "db"
            },
            "servedby": "mw-api-ext.eqiad.main-5ff7ffbb74-s5xdf"
        }""".trimIndent()
    }
}
