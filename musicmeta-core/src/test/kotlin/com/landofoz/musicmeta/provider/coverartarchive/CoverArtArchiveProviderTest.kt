package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.HttpResult
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
        httpClient.givenRedirectResult("release/abc123/front-", HttpResult.ClientError(404))
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
        // Given — both release and release-group 404 (no artwork)
        httpClient.givenRedirectResult("release/abc123/front-", HttpResult.ClientError(404))
        httpClient.givenRedirectResult("release-group/group1/front-", HttpResult.ClientError(404))
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

        // Then — Success but with empty URL (the redirect fetch returns the string as-is)
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
    fun `enrich returns back cover thumbnail from numeric key when small alias is absent`() = runTest {
        // Given -- CAA metadata whose thumbnails carry only the numeric keys, no deprecated aliases
        httpClient.givenJsonResponse(
            "release/back-numeric",
            """{
                "images": [
                    {"front": false, "types": ["Back"], "image": "https://archive.org/img/back.jpg", "thumbnails": {"250": "https://archive.org/img/back-250.jpg", "500": "https://archive.org/img/back-500.jpg", "1200": "https://archive.org/img/back-1200.jpg"}}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "back-numeric"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When -- enriching for back cover art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then -- the 250px thumbnail is still found
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/img/back-250.jpg", artwork.thumbnailUrl)
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
    fun `enrich returns NotFound when both release and group 404 the redirect`() = runTest {
        // Given — both release and release-group redirect fetches 404
        httpClient.givenRedirectResult("release/no-art/front-", HttpResult.ClientError(404))
        httpClient.givenRedirectResult("release-group/no-art-group/front-", HttpResult.ClientError(404))
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

    // --- ForTrack requests: musicBrainzId is a recording MBID, never a release MBID ---

    @Test
    fun `enrich serves ALBUM_ART via release-group for a track with an Official Album group`() = runTest {
        // Given — a track request whose identifiers carry a recording MBID (musicBrainzId) and the
        // release-group id MusicBrainzMapper.toTrackIdentifiers fills from the Official Album release
        httpClient.givenJsonResponse(
            "release-group/rg-studio/front-1200",
            "https://archive.org/image/rg-studio-1200.jpg",
        )
        httpClient.givenJsonResponse(
            "release-group/rg-studio/front-250",
            "https://archive.org/image/rg-studio-250.jpg",
        )
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "rec-studio",
                musicBrainzReleaseGroupId = "rg-studio",
            ),
            title = "Enter Sandman",
            artist = "Metallica",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — succeeds from the release-group endpoint, and the recording MBID is never sent to
        // the release endpoint (the only "release/" URL requested would 404 on a recording id)
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/image/rg-studio-1200.jpg", artwork.url)
        assertTrue(httpClient.requestedUrls.none { it.contains("release/rec-studio") })
    }

    @Test
    fun `enrich returns NotFound for track ALBUM_ART with no release-group id, no HTTP call`() = runTest {
        // Given — a track request with only the recording MBID, no Official Album release-group
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "rec-solo"),
            title = "Solo Track",
            artist = "Nobody",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound, and the doomed release-endpoint call (recording id as release id) never
        // happens: it would burn a throttled request and always 404
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich degrades a track's release-group art miss to NotFound, no release-endpoint call`() = runTest {
        // Given — a track request whose release-group id (e.g. a tier-3 bootleg fallback) has no
        // artwork on CAA at all
        httpClient.givenRedirectResult("release-group/rg-no-art/front-", HttpResult.ClientError(404))
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "rec-studio",
                musicBrainzReleaseGroupId = "rg-no-art",
            ),
            title = "Enter Sandman",
            artist = "Metallica",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — a plain NotFound (a healthy provider that found nothing), not an Error, and the
        // recording id is never sent to the release endpoint
        assertTrue("Expected NotFound but got $result", result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.none { it.contains("release/rec-studio") })
        assertTrue(httpClient.requestedUrls.all { it.contains("release-group/rg-no-art") })
    }

    @Test
    fun `enrich returns NotFound for track ALBUM_ART_BACK without a doomed HTTP call`() = runTest {
        // Given — a track request; ALBUM_ART_BACK is release-level with no release-group equivalent
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "rec-studio",
                musicBrainzReleaseGroupId = "rg-studio",
            ),
            title = "Enter Sandman",
            artist = "Metallica",
        )

        // When — enriching for back cover art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART_BACK)

        // Then — NotFound with zero HTTP calls, not a 404 from treating the recording id as a
        // release id
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns NotFound for track ALBUM_BOOKLET and CD_ART without a doomed HTTP call`() = runTest {
        // Given — a track request with both a recording MBID and a release-group id
        val request = EnrichmentRequest.ForTrack(
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = "rec-studio",
                musicBrainzReleaseGroupId = "rg-studio",
            ),
            title = "Enter Sandman",
            artist = "Metallica",
        )

        // When — enriching for booklet and CD art
        val booklet = provider.enrich(request, EnrichmentType.ALBUM_BOOKLET)
        val cdArt = provider.enrich(request, EnrichmentType.CD_ART)

        // Then — both cleanly NotFound, no HTTP call for either
        assertTrue(booklet is EnrichmentResult.NotFound)
        assertTrue(cdArt is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
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

    @Test
    fun `enrich ALBUM_ART degrades to null thumbnail when the thumbnail-size fetch is transient`() = runTest {
        // Given — full-size front cover resolves, but the thumbnail-size redirect call (a separate
        // request) hits a transient failure. Ticket 25: this must degrade the thumbnail, not throw
        // away the already-resolved full-size ALBUM_ART url.
        httpClient.givenJsonResponse(
            "release/abc123/front-1200",
            "https://archive.org/image/abc123-1200.jpg",
        )
        httpClient.givenError("release/abc123/front-250")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Success (not Error) with the full-size url intact and thumbnailUrl absent
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/image/abc123-1200.jpg", artwork.url)
        assertEquals(null, artwork.thumbnailUrl)
    }

    @Test
    fun `enrich ALBUM_ART degrades to null sizes when the front-image metadata fetch is transient`() = runTest {
        // Given — full-size front cover resolves, but the JSON metadata call fetchFrontImage uses
        // (a different endpoint, no /front-N suffix) hits a transient failure. Ticket 25: this must
        // degrade the front-image-derived sizes, not throw away the already-resolved ALBUM_ART url.
        // givenHttpResult only affects fetchJsonResult (the metadata GET), leaving the redirect-based
        // front-1200 call untouched even though its URL contains this key as a prefix.
        httpClient.givenJsonResponse(
            "release/abc123/front-1200",
            "https://archive.org/image/abc123-1200.jpg",
        )
        httpClient.givenHttpResult("release/abc123", HttpResult.ServerError(503))
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Success (not Error) with the full-size url intact and sizes absent
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/image/abc123-1200.jpg", artwork.url)
        assertEquals(null, artwork.sizes)
    }

    @Test
    fun `enrich ALBUM_ART degrades to null group thumbnail when the group thumbnail-size fetch is transient`() = runTest {
        // Given — no release id, so findArtwork falls back to the release-group branch; the
        // full-size group cover resolves, but the group thumbnail-size call is transient.
        httpClient.givenJsonResponse(
            "release-group/group1/front-1200",
            "https://archive.org/image/group1-1200.jpg",
        )
        httpClient.givenError("release-group/group1/front-250")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzReleaseGroupId = "group1"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Success (not Error) with the group full-size url intact and thumbnailUrl absent
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://archive.org/image/group1-1200.jpg", artwork.url)
        assertEquals(null, artwork.thumbnailUrl)
    }

    @Test
    fun `enrich ALBUM_ART still returns Error when the primary full-size fetch is transient`() = runTest {
        // Given — the primary full-size front-image fetch fails transiently; only the
        // supplementary thumbnail/front-image calls are allowed to degrade
        httpClient.givenError("release/abc123/front-1200")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "abc123"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Error with NETWORK ErrorKind, unchanged from pre-fix behaviour
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }
}
