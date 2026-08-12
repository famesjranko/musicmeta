package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ITunesApi.lookupByUpc]: a UPC is an identity lookup, not a search, so `resultCount: 0` must
 * resolve to `null` (mapped to `NotFound` by the caller) rather than being read as an error.
 *
 * [DISCOVERY_LIVE] is a trimmed copy of a live `itunes.apple.com/lookup?upc=724384960650`
 * response (2026-08-12) — the UPC is Daft Punk "Discovery" as carried by Deezer album 302127
 * (`api.deezer.com/album/302127`), the same album `docs/pitfalls.md` and this repo's fixtures
 * already use as a reference case.
 *
 * [MISS_LIVE] is a trimmed copy of a live `itunes.apple.com/lookup?upc=602547670342` response
 * (2026-08-12) — the barcode is Abbey Road (Deezer-supplied), confirmed absent from iTunes'
 * catalogue by the ticket's probe (`.scratch/design-review-plan/issues/09-itunes-upc-lookup.md`),
 * making it a genuine miss rather than the unusable `000000000000` placeholder (which now returns
 * 24 unrelated results and cannot serve as a negative control).
 */
class ITunesApiUpcLookupTest {

    private val httpClient = FakeHttpClient()
    private val api = ITunesApi(httpClient, RateLimiter(0))

    @Test
    fun `resolves a collection id for a barcode iTunes carries`() = runTest {
        // Given - a live response for a barcode known to be in iTunes' catalogue
        httpClient.givenJsonResponse("upc=724384960650", DISCOVERY_LIVE)

        // When - looking up the album by UPC
        val result = api.lookupByUpc("724384960650")

        // Then - the collection it identifies comes back, not a ranked guess
        assertEquals(697194953L, result?.collectionId)
        assertEquals("Discovery", result?.collectionName)
    }

    @Test
    fun `returns null for a barcode absent from the catalogue`() = runTest {
        // Given - a live resultCount 0 response for a barcode confirmed absent from iTunes
        httpClient.givenJsonResponse("upc=602547670342", MISS_LIVE)

        // When - looking up the album by UPC
        val result = api.lookupByUpc("602547670342")

        // Then - a genuine miss is null, not an exception
        assertNull(result)
    }

    private companion object {
        const val DISCOVERY_LIVE = """
            {"resultCount":1,"results":[
              {"wrapperType":"collection","collectionType":"Album","artistId":5468295,
               "collectionId":697194953,"artistName":"Daft Punk","collectionName":"Discovery",
               "trackCount":14,"country":"USA","releaseDate":"2001-03-12T08:00:00Z",
               "primaryGenreName":"Dance"}
            ]}
        """

        const val MISS_LIVE = """
            {"resultCount":0,"results":[]}
        """
    }
}
