package com.landofoz.musicmeta.provider.coverartarchive

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
        api = CoverArtArchiveApi(httpClient)
    }

    @Test
    fun `parseImageList extracts types array from each image`() = runTest {
        // Given -- CAA JSON with images containing types arrays
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

        // When -- fetching artwork metadata
        val metadata = api.getArtworkMetadata("test-types")

        // Then -- each image has its types parsed
        assertEquals(3, metadata!!.size)
        assertEquals(listOf("Front"), metadata[0].types)
        assertEquals(listOf("Back"), metadata[1].types)
        assertEquals(listOf("Booklet"), metadata[2].types)
    }

    @Test
    fun `parseImageList returns empty types when types array missing`() = runTest {
        // Given -- CAA JSON with an image that has no types field
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

        // When -- fetching artwork metadata
        val metadata = api.getArtworkMetadata("no-types")

        // Then -- types defaults to empty list
        assertEquals(1, metadata!!.size)
        assertTrue(metadata[0].types.isEmpty())
    }

    @Test
    fun `CoverArtArchiveImage with Back type is identifiable`() {
        // Given -- an image with types=["Back"]
        val image = CoverArtArchiveImage(
            front = false,
            url = "https://archive.org/img/back.jpg",
            thumbnails = emptyMap(),
            types = listOf("Back"),
        )

        // Then -- it can be identified as a back cover
        assertTrue("Back" in image.types)
    }

    @Test
    fun `CoverArtArchiveImage with Booklet type is identifiable`() {
        // Given -- an image with types=["Booklet"]
        val image = CoverArtArchiveImage(
            front = false,
            url = "https://archive.org/img/booklet.jpg",
            thumbnails = emptyMap(),
            types = listOf("Booklet"),
        )

        // Then -- it can be identified as a booklet
        assertTrue("Booklet" in image.types)
    }
}
