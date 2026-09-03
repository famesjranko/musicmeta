package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FanartTvProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: FanartTvProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = FanartTvProvider(
            projectKey = "test-project-key",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
    }

    @Test
    fun `enrich returns artist photo`() = runTest {
        // Given - Fanart.tv returns artist images including a thumbnail
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - success with the thumbnail artwork
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/thumb1.jpg",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns artist background`() = runTest {
        // Given - Fanart.tv returns artist images including a background
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist background
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then - success with the background artwork
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/bg1.jpg",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns artist logo`() = runTest {
        // Given - Fanart.tv returns artist images including a logo
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist logo
        val result = provider.enrich(request, EnrichmentType.ARTIST_LOGO)

        // Then - success with the logo artwork
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/logo1.png",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns NotFound when no MBID`() = runTest {
        // Given - no musicBrainzId in identifiers
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When - enriching for artist background
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then - NotFound because there is no MBID to query with
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when API returns no images`() = runTest {
        // Given - empty image arrays
        httpClient.givenJsonResponse("fanart.tv", EMPTY_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist background
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then - NotFound because the API returned no images
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for an unknown artist mbid`() = runTest {
        // Given - an unrecognised artist MBID, which fanart.tv answers 200 with an empty object
        httpClient.givenJsonResponse("fanart.tv", UNKNOWN_ARTIST_JSON)
        val request = artistRequest()

        // When - enriching for artist background
        val result = provider.enrich(request, EnrichmentType.ARTIST_BACKGROUND)

        // Then - NotFound because the bare {} response carries no image categories at all
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when API key is blank`() = runTest {
        // Given - a provider configured with a blank project key
        val blankProvider = FanartTvProvider(
            projectKey = "",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
        val request = artistRequest()

        // When - enriching for artist photo
        val result = blankProvider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound because a blank key short-circuits the request
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `a project key holding a reserved character reaches the URL percent-encoded`() = runTest {
        // Given - a provider whose key carries a pipe, which DefaultHttpClient cannot send raw
        val keyed = FanartTvProvider(
            projectKey = "abc|def",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)

        // When - enriching for artist photo
        keyed.enrich(artistRequest(), EnrichmentType.ARTIST_PHOTO)

        // Then - the key went out encoded, so either shipped client can send it
        assertTrue(
            "key was not encoded: ${httpClient.requestedUrls}",
            httpClient.requestedUrls.single().contains("api_key=abc%7Cdef"),
        )
    }

    private fun artistRequest() = EnrichmentRequest.ForArtist(
        identifiers = EnrichmentIdentifiers(
            musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711",
        ),
        name = "Radiohead",
    )

    @Test
    fun `enrich returns NotFound when image objects have no url field`() = runTest {
        // Given - Fanart.tv returns image arrays with objects missing the url field
        httpClient.givenJsonResponse("fanart.tv", """{
            "artistthumb": [{"id": "12345", "likes": "3"}],
            "artistbackground": [{"id": "67890"}],
            "hdmusiclogo": []
        }""")
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound because extractUrls filters out objects without a valid url
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich ALBUM_ART returns NotFound without a request when no releaseGroupMbid`() = runTest {
        // Given - an artist MBID but no release group id, and an artist document that does carry
        // album art nested under "albums"
        httpClient.givenJsonResponse("fanart.tv", ALBUM_VIA_ARTIST_JSON)
        val request = artistRequest()

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, and no rate-limited request spent on the artist endpoint
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns artist banner`() = runTest {
        // Given - Fanart.tv returns images with a banner URL
        httpClient.givenJsonResponse("fanart.tv", BANNER_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist banner
        val result = provider.enrich(request, EnrichmentType.ARTIST_BANNER)

        // Then - success with banner artwork
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://assets.fanart.tv/fanart/banner1.jpg",
            (data as EnrichmentData.Artwork).url,
        )
    }

    @Test
    fun `enrich returns NotFound when no banners`() = runTest {
        // Given - Fanart.tv returns images with empty banners
        httpClient.givenJsonResponse("fanart.tv", EMPTY_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist banner
        val result = provider.enrich(request, EnrichmentType.ARTIST_BANNER)

        // Then - NotFound because no banner images available
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns artwork with sizes when multiple images exist`() = runTest {
        // Given - Fanart.tv returns multiple thumbnails for an artist
        httpClient.givenJsonResponse("fanart.tv", MULTI_THUMB_JSON)
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - artwork has sizes list with all image variants
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://assets.fanart.tv/fanart/thumb1.jpg", artwork.url)
        assertNotNull(artwork.sizes)
        assertEquals(3, artwork.sizes!!.size)
    }

    @Test
    fun `enrich selects the most-liked thumbnail even when it is not first`() = runTest {
        // Given - the highest-liked thumbnail is listed second, not first
        httpClient.givenJsonResponse("fanart.tv", NOT_FIRST_MOST_LIKED_JSON)
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - the most-liked image wins, not list order
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://assets.fanart.tv/fanart/thumb-most-liked.jpg", artwork.url)
    }

    @Test
    fun `enrich keeps list order among tied likes counts`() = runTest {
        // Given - two thumbnails tied on likes; the first in list order should win
        httpClient.givenJsonResponse("fanart.tv", TIED_LIKES_JSON)
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - the first tied entry is selected
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://assets.fanart.tv/fanart/thumb-tied-first.jpg", artwork.url)
    }

    @Test
    fun `enrich ranks missing or garbage likes lowest without crashing`() = runTest {
        // Given - one thumbnail has garbage likes, one is missing likes entirely, one has a
        // real positive count -- neither of the first two should ever beat the real count
        httpClient.givenJsonResponse("fanart.tv", GARBAGE_LIKES_JSON)
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - no crash, and the only thumbnail with a real likes count wins
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://assets.fanart.tv/fanart/thumb-real-likes.jpg", artwork.url)
    }

    @Test
    fun `enrich ALBUM_ART selects the most-liked album cover even when it is not first`() = runTest {
        // Given - the album endpoint lists the lower-liked cover first
        httpClient.givenJsonResponse("/albums/", ALBUM_NOT_FIRST_MOST_LIKED_JSON)
        val request = albumRequest()

        // When - enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the most-liked cover wins, not list order
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://fanart.tv/album-cover-most-liked.jpg", artwork.url)
    }

    @Test
    fun `enrich returns artwork without sizes when single image`() = runTest {
        // Given - Fanart.tv returns a single thumbnail
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - artwork has no sizes (only 1 image, not worth listing)
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertNull(artwork.sizes)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API fails`() = runTest {
        // Given - simulate an IOException from the HTTP layer
        httpClient.givenIoException("fanart.tv")
        val request = artistRequest()

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - Error with NETWORK kind because IOException maps to ErrorKind.NETWORK
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals("fanarttv", result.provider)
    }

    @Test
    fun `enrich ALBUM_ART uses album endpoint when releaseGroupMbid available`() = runTest {
        // Given - request with both artist MBID and release group MBID
        httpClient.givenJsonResponse("/albums/", ALBUM_ENDPOINT_JSON)
        val request = albumRequest()

        // When - enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - Success from album endpoint (URL contains /albums/)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://fanart.tv/album-cover.jpg", data.url)
        assertTrue(httpClient.requestedUrls.any { it.contains("/albums/") })
    }

    @Test
    fun `enrich ALBUM_ART does not fall back to artist endpoint when album endpoint misses`() = runTest {
        // Given - the album endpoint has nothing for this release group (a 404, not a transient:
        // a 429/5xx is now an Error, so givenError() here would assert the wrong path), and the
        // artist endpoint would have answered
        httpClient.givenHttpResult("/albums/", HttpResult.ClientError(404))
        httpClient.givenJsonResponse("fanart.tv", ALBUM_VIA_ARTIST_JSON)
        val request = albumRequest()

        // When - enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, and the artist endpoint was never called with the release id
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.none { it.contains("release-mbid") })
    }

    @Test
    fun `ALBUM_ART and CD_ART capabilities require a release group id`() {
        // Given - the provider's declared capabilities list
        val albumTypes = setOf(EnrichmentType.ALBUM_ART, EnrichmentType.CD_ART)
        // When - filtering for the ALBUM_ART and CD_ART capabilities
        // Then - each declares MUSICBRAINZ_RELEASE_GROUP_ID as its identifier requirement
        provider.capabilities.filter { it.type in albumTypes }.forEach {
            assertEquals(IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID, it.identifierRequirement)
        }
    }

    @Test
    fun `enrich CD_ART uses album endpoint when releaseGroupMbid available`() = runTest {
        // Given - request with both artist MBID and release group MBID
        httpClient.givenJsonResponse("/albums/", ALBUM_ENDPOINT_JSON)
        val request = albumRequest()

        // When - enriching for CD_ART
        val result = provider.enrich(request, EnrichmentType.CD_ART)

        // Then - Success from album endpoint
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://fanart.tv/cd-art.jpg", data.url)
        assertTrue(httpClient.requestedUrls.any { it.contains("/albums/") })
    }

    @Test
    fun `enrich ALBUM_ART returns NotFound when the album endpoint has no covers`() = runTest {
        // Given - the album endpoint returns nothing for this release group (a 404; a transient
        // is an Error, which is a different test)
        httpClient.givenHttpResult("/albums/", HttpResult.ClientError(404))
        val request = albumRequest()

        // When - enriching for ALBUM_ART
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because both endpoints have no album covers
        assertTrue(result is EnrichmentResult.NotFound)
    }

    // musicBrainzId is the *release* id on a ForAlbum request — MusicBrainzMapper.toAlbumIdentifiers
    private fun albumRequest() = EnrichmentRequest.ForAlbum(
        identifiers = EnrichmentIdentifiers(
            musicBrainzId = "release-mbid",
            musicBrainzReleaseGroupId = "rg-mbid",
        ),
        title = "OK Computer",
        artist = "Radiohead",
    )

    private companion object {
        // synthetic: no ground truth available for v3 field-level shapes (the per-category
        // list is documented only under v3.2)
        val MULTI_THUMB_JSON = """
            {
              "artistthumb": [
                {"url": "https://assets.fanart.tv/fanart/thumb1.jpg", "id": "100", "likes": "5"},
                {"url": "https://assets.fanart.tv/fanart/thumb2.jpg", "id": "101", "likes": "3"},
                {"url": "https://assets.fanart.tv/fanart/thumb3.jpg", "id": "102", "likes": "1"}
              ],
              "artistbackground": [],
              "hdmusiclogo": []
            }
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val BANNER_IMAGES_JSON = """
            {
              "artistthumb": [],
              "artistbackground": [],
              "hdmusiclogo": [],
              "musicbanner": [{"url": "https://assets.fanart.tv/fanart/banner1.jpg"}]
            }
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val ARTIST_IMAGES_JSON = """
            {
              "artistthumb": [{"url": "https://assets.fanart.tv/fanart/thumb1.jpg"}],
              "artistbackground": [{"url": "https://assets.fanart.tv/fanart/bg1.jpg"}],
              "hdmusiclogo": [{"url": "https://assets.fanart.tv/fanart/logo1.png"}]
            }
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes (this represents a
        // recognised artist with no images of any type — distinct from an unrecognised MBID,
        // which the live API answers with a bare `{}`, see UNKNOWN_ARTIST_JSON below)
        val EMPTY_IMAGES_JSON = """
            {
              "artistthumb": [],
              "artistbackground": [],
              "hdmusiclogo": [],
              "musicbanner": []
            }
        """.trimIndent()

        // synthetic: unrecognised MBID answers 200 {} (probed 2026-08-11, GET /v3/music/{mbid})
        const val UNKNOWN_ARTIST_JSON = "{}"

        // synthetic: no ground truth available for v3 field-level shapes
        val NOT_FIRST_MOST_LIKED_JSON = """
            {
              "artistthumb": [
                {"url": "https://assets.fanart.tv/fanart/thumb-low-likes.jpg", "id": "200", "likes": "2"},
                {"url": "https://assets.fanart.tv/fanart/thumb-most-liked.jpg", "id": "201", "likes": "10"}
              ],
              "artistbackground": [],
              "hdmusiclogo": []
            }
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val TIED_LIKES_JSON = """
            {
              "artistthumb": [
                {"url": "https://assets.fanart.tv/fanart/thumb-tied-first.jpg", "id": "300", "likes": "5"},
                {"url": "https://assets.fanart.tv/fanart/thumb-tied-second.jpg", "id": "301", "likes": "5"}
              ],
              "artistbackground": [],
              "hdmusiclogo": []
            }
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val GARBAGE_LIKES_JSON = """
            {
              "artistthumb": [
                {"url": "https://assets.fanart.tv/fanart/thumb-garbage-likes.jpg", "id": "400", "likes": "not-a-number"},
                {"url": "https://assets.fanart.tv/fanart/thumb-missing-likes.jpg", "id": "401"},
                {"url": "https://assets.fanart.tv/fanart/thumb-real-likes.jpg", "id": "402", "likes": "1"}
              ],
              "artistbackground": [],
              "hdmusiclogo": []
            }
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val ALBUM_NOT_FIRST_MOST_LIKED_JSON = """
            {"rg-mbid": {"albumcover": [
                {"url": "https://fanart.tv/album-cover-low-likes.jpg", "id": "1", "likes": "1"},
                {"url": "https://fanart.tv/album-cover-most-liked.jpg", "id": "2", "likes": "20"}
              ], "cdart": []}}
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val ALBUM_ENDPOINT_JSON = """
            {"rg-mbid": {"albumcover": [{"url": "https://fanart.tv/album-cover.jpg", "id": "1", "likes": "5"}], "cdart": [{"url": "https://fanart.tv/cd-art.jpg", "id": "2", "likes": "3"}]}}
        """.trimIndent()

        // synthetic: no ground truth available for v3 field-level shapes
        val ALBUM_VIA_ARTIST_JSON = """
            {
              "artistthumb": [],
              "artistbackground": [],
              "hdmusiclogo": [],
              "musicbanner": [],
              "albums": {
                "rg-mbid": {
                  "albumcover": [{"url": "https://assets.fanart.tv/fanart/album-cover-artist.jpg", "id": "10", "likes": "2"}],
                  "cdart": []
                },
                "other-rg-mbid": {
                  "albumcover": [{"url": "https://assets.fanart.tv/fanart/other-album-cover.jpg", "id": "11", "likes": "9"}],
                  "cdart": []
                }
              }
            }
        """.trimIndent()
    }
}
