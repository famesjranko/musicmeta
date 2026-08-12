package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ITunesApi.lookupByUpc]: a UPC is an identity lookup, not a search, so `resultCount: 0` must
 * resolve to an empty list (mapped to `NotFound` by the caller) rather than being read as an
 * error. The barcode-to-artist gate lives in `ITunesProvider.lookupByBarcode`, not here — this
 * call only proves iTunes indexes the barcode somewhere, so its own tests pin the raw candidate
 * list, unfiltered.
 */
class ITunesApiUpcLookupTest {

    private val httpClient = FakeHttpClient()
    private val api = ITunesApi(httpClient, RateLimiter(0))

    @Test
    fun `resolves every collection carried under a barcode`() = runTest {
        // Given - a live response for a barcode known to be in iTunes' catalogue
        httpClient.givenJsonResponse("upc=724384960650", DISCOVERY_LIVE)

        // When - looking up the album by UPC
        val result = api.lookupByUpc("724384960650")

        // Then - the collection it identifies comes back, not a ranked guess
        assertEquals(1, result.size)
        assertEquals(697194953L, result.single().collectionId)
        assertEquals("Discovery", result.single().collectionName)
    }

    @Test
    fun `returns an empty list for a barcode absent from the catalogue`() = runTest {
        // Given - a live resultCount 0 response for a barcode confirmed absent from iTunes
        httpClient.givenJsonResponse("upc=602547670342", MISS_LIVE)

        // When - looking up the album by UPC
        val result = api.lookupByUpc("602547670342")

        // Then - a genuine miss is an empty list, not an exception
        assertTrue(result.isEmpty())
    }

    private companion object {
        // captured 2026-08-12: GET /lookup?upc=724384960650, trimmed — Daft Punk "Discovery" as
        // carried by Deezer album 302127 (api.deezer.com/album/302127), the same reference album
        // this repo's other fixtures use
        const val DISCOVERY_LIVE = """
            {"resultCount":1,"results":[
              {"wrapperType":"collection","collectionType":"Album","artistId":5468295,
               "collectionId":697194953,"artistName":"Daft Punk","collectionName":"Discovery",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/fd/4a/77/fd4a77db-0ebc-d043-41a2-f32fa1bb0fb4/dj.qrikkdwj.jpg/100x100bb.jpg",
               "trackCount":14,"country":"USA","releaseDate":"2001-03-12T08:00:00Z",
               "primaryGenreName":"Dance"}
            ]}
        """

        // captured 2026-08-12: GET /lookup?upc=602547670342, trimmed — Abbey Road (Deezer-supplied
        // barcode), confirmed absent from iTunes' catalogue; not the unusable 000000000000
        // placeholder, which now returns 24 unrelated results and cannot serve as a negative
        // control
        const val MISS_LIVE = """
            {"resultCount":0,"results":[]}
        """
    }
}
