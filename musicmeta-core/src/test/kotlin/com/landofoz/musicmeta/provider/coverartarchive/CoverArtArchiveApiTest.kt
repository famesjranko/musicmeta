package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoverArtArchiveApiTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var api: CoverArtArchiveApi

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        api = CoverArtArchiveApi(httpClient, RateLimiter(0))
    }

    @Test
    fun `every call goes through the rate limiter`() = runTest {
        // Given - a frozen clock, so the limiter waits its full interval ahead of every call
        val limited = CoverArtArchiveApi(httpClient, RateLimiter(1000, clock = { 0L }))

        // When - each of the three endpoints is called once
        limited.getArtworkUrl("mbid")
        limited.getGroupArtworkUrl("rgid")
        limited.getArtworkMetadata("mbid")

        // Then - three intervals were waited out; an unwrapped call would not advance the clock
        assertEquals(3000L, testScheduler.currentTime)
    }

    @Test
    fun `getGroupArtworkUrl requests the release-group front-cover endpoint`() = runTest {
        // Given - no stub; the request URL is what this test pins
        // When - fetching front-cover art for a release-group id
        api.getGroupArtworkUrl("rg-studio", 1200)

        // Then - the exact path shape live-verified against coverartarchive.org (307 redirect to
        // the archive.org image, same convention as the release endpoint's own redirect handling)
        assertEquals(
            listOf("https://coverartarchive.org/release-group/rg-studio/front-1200"),
            httpClient.requestedUrls,
        )
    }

    @Test
    fun `parseImageList extracts types array from each image`() = runTest {
        // Given - CAA JSON with images containing types arrays
        httpClient.givenJsonResponse(
            "release/test-types",
            """{
                "images": [
                    {
                        "front": true,
                        "image": "https://archive.org/img/front.jpg",
                        "thumbnails": {},
                        "types": ["Front"]
                    },
                    {
                        "front": false,
                        "image": "https://archive.org/img/back.jpg",
                        "thumbnails": {},
                        "types": ["Back"]
                    },
                    {
                        "front": false,
                        "image": "https://archive.org/img/booklet.jpg",
                        "thumbnails": {},
                        "types": ["Booklet"]
                    }
                ]
            }""",
        )

        // When - fetching artwork metadata
        val metadata = api.getArtworkMetadata("test-types")

        // Then - each image has its types parsed
        assertEquals(3, metadata!!.size)
        assertEquals(listOf("Front"), metadata[0].types)
        assertEquals(listOf("Back"), metadata[1].types)
        assertEquals(listOf("Booklet"), metadata[2].types)
    }

    @Test
    fun `parseImageList returns empty types when types array missing`() = runTest {
        // Given - CAA JSON with an image that has no types field
        httpClient.givenJsonResponse(
            "release/no-types",
            """{
                "images": [
                    {
                        "front": true,
                        "image": "https://archive.org/img/front.jpg",
                        "thumbnails": {}
                    }
                ]
            }""",
        )

        // When - fetching artwork metadata
        val metadata = api.getArtworkMetadata("no-types")

        // Then - types defaults to empty list
        assertEquals(1, metadata!!.size)
        assertTrue(metadata[0].types.isEmpty())
    }

    @Test
    fun `CoverArtArchiveImage with Back type is identifiable`() {
        // Given - an image with types=["Back"]
        val image = CoverArtArchiveImage(
            front = false,
            url = "https://archive.org/img/back.jpg",
            thumbnails = emptyMap(),
            types = listOf("Back"),
        )

        // Then - it can be identified as a back cover
        assertTrue("Back" in image.types)
    }

    @Test
    fun `CoverArtArchiveImage with Booklet type is identifiable`() {
        // Given - an image with types=["Booklet"]
        val image = CoverArtArchiveImage(
            front = false,
            url = "https://archive.org/img/booklet.jpg",
            thumbnails = emptyMap(),
            types = listOf("Booklet"),
        )

        // Then - it can be identified as a booklet
        assertTrue("Booklet" in image.types)
    }
}
