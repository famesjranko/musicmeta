package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
