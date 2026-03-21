package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.ArtworkSize
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoverArtArchiveProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: CoverArtArchiveProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = CoverArtArchiveProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `enrich returns artwork URL for release with front cover`() = runTest {
        // Given — CAA has front cover images for release abc123
        httpClient.givenJsonResponse(
            "release/abc123/front-1200",
            "https://archive.org/image/abc123-1200.jpg",
        )
        httpClient.givenJsonResponse(
            "release/abc123/front-250",
            "https://archive.org/image/abc123-250.jpg",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "abc123",
                musicBrainzReleaseGroupId = "group1",
            ),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — returns 1200px main URL, 250px thumbnail, and full confidence
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/image/abc123-1200.jpg", artwork.url)
        assertEquals("https://archive.org/image/abc123-250.jpg", artwork.thumbnailUrl)
        assertEquals(1.0f, result.confidence, 0.01f)
    }

    @Test
    fun `enrich falls back to release-group when release has no art`() = runTest {
        // Given — release has no art (404), but release-group does
        httpClient.givenError("release/abc123/front-")
        httpClient.givenJsonResponse(
            "release-group/group1/front-1200",
            "https://archive.org/image/group1-1200.jpg",
        )
        httpClient.givenJsonResponse(
            "release-group/group1/front-250",
            "https://archive.org/image/group1-250.jpg",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "abc123",
                musicBrainzReleaseGroupId = "group1",
            ),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — falls back to release-group artwork
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/image/group1-1200.jpg", artwork.url)
    }

    @Test
    fun `enrich returns NotFound when no MBID available`() = runTest {
        // Given — request with empty identifiers (no MusicBrainz IDs)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(),
            title = "Test",
            artist = "Test",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because CAA requires an MBID
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns correct thumbnail URLs`() = runTest {
        // Given — CAA has both 1200px and 250px images for release
        httpClient.givenJsonResponse(
            "release/abc123/front-1200",
            "https://archive.org/image/abc123-1200.jpg",
        )
        httpClient.givenJsonResponse(
            "release/abc123/front-250",
            "https://archive.org/image/abc123-250.jpg",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            title = "Test",
            artist = "Test",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — main URL is 1200px, thumbnail is 250px
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(artwork.url.contains("1200"))
        assertTrue(artwork.thumbnailUrl?.contains("250") == true)
    }

    @Test
    fun `enrich returns NotFound when neither release nor group has art`() = runTest {
        // Given — both release and release-group return errors (no artwork)
        httpClient.givenError("release/abc123/front-")
        httpClient.givenError("release-group/group1/front-")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "abc123",
                musicBrainzReleaseGroupId = "group1",
            ),
            title = "Test",
            artist = "Test",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound after exhausting both fallback levels
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich works with only release-group ID`() = runTest {
        // Given — request has only a release-group ID, no release MBID
        httpClient.givenJsonResponse(
            "release-group/group1/front-1200",
            "https://archive.org/image/group1-1200.jpg",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzReleaseGroupId = "group1"),
            title = "Test",
            artist = "Test",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — succeeds using the release-group path directly
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich returns artwork with empty string redirect URL`() = runTest {
        // Given — CAA redirect returns an empty string URL for the release
        httpClient.givenJsonResponse("release/abc123/front-1200", "")
        httpClient.givenJsonResponse("release/abc123/front-250", "")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            title = "Test",
            artist = "Test",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Success but with empty URL (fetchRedirectUrl returns the string as-is)
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("", artwork.url)
    }

    @Test
    fun `enrich returns artwork with sizes from metadata endpoint`() = runTest {
        // Given -- CAA has front cover and JSON metadata with thumbnails
        // Register specific redirect keys FIRST so they match before the general key
        httpClient.givenJsonResponse(
            "release/sizes-rel/front-1200",
            "https://archive.org/image/sizes-rel-1200.jpg",
        )
        httpClient.givenJsonResponse(
            "release/sizes-rel/front-250",
            "https://archive.org/image/sizes-rel-250.jpg",
        )
        // Metadata endpoint (no /front-* suffix). FakeHttpClient uses firstOrNull,
        // so the more specific front-* keys above match redirect calls first.
        httpClient.givenJsonResponse(
            "release/sizes-rel",
            """{
                "images": [
                    {
                        "front": true,
                        "image": "https://archive.org/image/sizes-rel-full.jpg",
                        "thumbnails": {
                            "250": "https://archive.org/image/sizes-rel-250.jpg",
                            "500": "https://archive.org/image/sizes-rel-500.jpg",
                            "1200": "https://archive.org/image/sizes-rel-1200.jpg",
                            "small": "https://archive.org/image/sizes-rel-small.jpg",
                            "large": "https://archive.org/image/sizes-rel-large.jpg"
                        }
                    }
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "sizes-rel"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then -- returns artwork with populated sizes list
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertNotNull(artwork.sizes)
        assertTrue(artwork.sizes!!.size >= 2)
        assertTrue(artwork.sizes!!.any { it.label == "250" })
        assertTrue(artwork.sizes!!.any { it.label == "1200" })
    }

    // --- ALBUM_ART_BACK tests ---

    @Test
    fun `enrich returns back cover artwork when CAA has Back image`() = runTest {
        // Given -- CAA metadata has a Back image
        httpClient.givenJsonResponse(
            "release/back-rel",
            """{
                "images": [
                    {"front": true, "types": ["Front"], "image": "https://archive.org/img/front.jpg", "thumbnails": {"small": "https://archive.org/img/front-250.jpg", "large": "https://archive.org/img/front-500.jpg"}},
                    {"front": false, "types": ["Back"], "image": "https://archive.org/img/back.jpg", "thumbnails": {"small": "https://archive.org/img/back-250.jpg", "large": "https://archive.org/img/back-500.jpg"}}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "back-rel"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for back cover art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then -- returns Success with back cover artwork
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/img/back.jpg", artwork.url)
        assertEquals("https://archive.org/img/back-250.jpg", artwork.thumbnailUrl)
        assertEquals(1.0f, result.confidence, 0.01f)
    }

    @Test
    fun `enrich returns NotFound for ALBUM_ART_BACK when no back image exists`() = runTest {
        // Given -- CAA metadata has only a Front image, no Back
        httpClient.givenJsonResponse(
            "release/front-only",
            """{
                "images": [
                    {"front": true, "types": ["Front"], "image": "https://archive.org/img/front.jpg", "thumbnails": {"small": "https://archive.org/img/front-250.jpg"}}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "front-only"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for back cover art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then -- NotFound because no Back image
        assertTrue("Expected NotFound but got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns back cover artwork with sizes from thumbnails`() = runTest {
        // Given -- CAA metadata has a Back image with multiple thumbnail sizes
        httpClient.givenJsonResponse(
            "release/back-sizes",
            """{
                "images": [
                    {"front": false, "types": ["Back"], "image": "https://archive.org/img/back.jpg", "thumbnails": {"small": "https://archive.org/img/back-250.jpg", "large": "https://archive.org/img/back-500.jpg", "1200": "https://archive.org/img/back-1200.jpg"}}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "back-sizes"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for back cover art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then -- artwork includes sizes from thumbnails
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertNotNull(artwork.sizes)
        assertTrue(artwork.sizes!!.size >= 2)
        assertTrue(artwork.sizes!!.any { it.label == "small" })
        assertTrue(artwork.sizes!!.any { it.label == "large" })
    }

    @Test
    fun `enrich returns NotFound for ALBUM_ART_BACK when no release ID`() = runTest {
        // Given -- request has no release MBID
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzReleaseGroupId = "group1"),
            title = "Test",
            artist = "Test",
        )

        // When -- enriching for back cover art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then -- NotFound because back/booklet require release ID
        assertTrue(result is EnrichmentResult.NotFound)
    }

    // --- ALBUM_BOOKLET tests ---

    @Test
    fun `enrich returns booklet artwork when CAA has Booklet image`() = runTest {
        // Given -- CAA metadata has a Booklet image
        httpClient.givenJsonResponse(
            "release/booklet-rel",
            """{
                "images": [
                    {"front": true, "types": ["Front"], "image": "https://archive.org/img/front.jpg", "thumbnails": {"small": "https://archive.org/img/front-250.jpg"}},
                    {"front": false, "types": ["Booklet"], "image": "https://archive.org/img/booklet.jpg", "thumbnails": {"small": "https://archive.org/img/booklet-250.jpg", "large": "https://archive.org/img/booklet-500.jpg"}}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "booklet-rel"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for booklet art
        val result = provider.enrich(request, EnrichmentType.ALBUM_BOOKLET)

        // Then -- returns Success with booklet artwork
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/img/booklet.jpg", artwork.url)
        assertEquals("https://archive.org/img/booklet-250.jpg", artwork.thumbnailUrl)
    }

    @Test
    fun `enrich returns NotFound for ALBUM_BOOKLET when no booklet image exists`() = runTest {
        // Given -- CAA metadata has only Front and Back, no Booklet
        httpClient.givenJsonResponse(
            "release/no-booklet",
            """{
                "images": [
                    {"front": true, "types": ["Front"], "image": "https://archive.org/img/front.jpg", "thumbnails": {}},
                    {"front": false, "types": ["Back"], "image": "https://archive.org/img/back.jpg", "thumbnails": {}}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "no-booklet"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for booklet art
        val result = provider.enrich(request, EnrichmentType.ALBUM_BOOKLET)

        // Then -- NotFound because no Booklet image
        assertTrue("Expected NotFound but got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when both release and group return null redirect`() = runTest {
        // Given — both release and release-group fetchRedirectUrl return null (404)
        httpClient.givenError("release/no-art/front-")
        httpClient.givenError("release-group/no-art-group/front-")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "no-art",
                musicBrainzReleaseGroupId = "no-art-group",
            ),
            title = "Missing Art",
            artist = "Nobody",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound after exhausting both release and release-group paths
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when metadata endpoint throws IOException`() = runTest {
        // Given — metadata endpoint throws IOException (network failure)
        // ALBUM_ART_BACK goes straight to getArtworkMetadata (fetchJsonResult) without a redirect call
        httpClient.givenIoException("release/err-rel")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "err-rel"),
            title = "Error Album",
            artist = "Error Artist",
        )

        // When — enriching for back cover art (triggers metadata fetch which throws)
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then — Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }
}
