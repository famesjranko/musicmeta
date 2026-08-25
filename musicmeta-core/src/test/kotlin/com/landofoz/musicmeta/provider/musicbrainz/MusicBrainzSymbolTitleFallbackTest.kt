package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Enricher-level (mocked-HTTP) coverage for the symbol-title fallback: an album MusicBrainz stores
 * as `F♯ A♯ ∞` that no ASCII spelling can be searched for.
 *
 * The caller's own `release:"F# A# (Infinity)"` search is stubbed with the body MusicBrainz really
 * answers it with — 200 and `{"releases": []}` (live, 2026-08-10) — not left unstubbed to 404 into
 * the same empty list: the failure being fixed is an empty pool from a *successful* search.
 */
class MusicBrainzSymbolTitleFallbackTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    /** MusicBrainz's real answer to the caller's ASCII spelling: a 200 with no releases at all. */
    private fun givenCallerTitleSearchFindsNothing() {
        httpClient.givenJsonResponse(CALLER_TITLE_SEARCH_URL, """{"count": 0, "offset": 0, "releases": []}""")
    }

    private fun givenArtistResolves() {
        httpClient.givenJsonResponse(
            ARTIST_SEARCH_URL,
            """
            {
              "artists": [{
                "id": "$ARTIST_MBID",
                "score": 100,
                "name": "Godspeed You! Black Emperor",
                "tags": [{"name": "post-rock", "count": 8}]
              }]
            }
            """.trimIndent(),
        )
    }

    private fun releaseGroupsJson(titles: List<String>): String {
        val entries = titles.joinToString(",") { title ->
            """{"id": "rg-${title.hashCode()}", "title": ${JSONObject.quote(title)}, "primary-type": "Album"}"""
        }
        return """{"release-groups": [$entries]}"""
    }

    private fun givenSymbolTitleReleaseSearchHits() {
        httpClient.givenJsonResponse(
            SYMBOL_TITLE_SEARCH_URL,
            """
            {
              "releases": [{
                "id": "$RELEASE_MBID",
                "score": 100,
                "title": "F♯ A♯ ∞",
                "artist-credit": [{"artist": {"id": "$ARTIST_MBID", "name": "Godspeed You! Black Emperor"}}],
                "release-group": {"id": "$RELEASE_GROUP_MBID", "tags": [{"name": "post-rock", "count": 8}]}
              }]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an ASCII spelling of a symbol title resolves through the artist's release groups`() = runTest {
        // Given - the caller's own title search returns no releases, the artist resolves, and the
        // browse carries the release group under MusicBrainz's own symbol spelling
        givenCallerTitleSearchFindsNothing()
        givenArtistResolves()
        httpClient.givenJsonResponse(
            BROWSE_PAGE_0_URL,
            """{"release-groups": [{"id": "$RELEASE_GROUP_MBID", "title": "F♯ A♯ ∞", "primary-type": "Album"}]}""",
        )
        givenSymbolTitleReleaseSearchHits()
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - the release behind the symbol title is resolved
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(RELEASE_MBID, success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(RELEASE_GROUP_MBID, success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    @Test
    fun `a release reached by folding the caller's spelling reports FUZZY_NAME`() = runTest {
        // Given - the same fallback route, which matches on a fold rather than on the title itself
        givenCallerTitleSearchFindsNothing()
        givenArtistResolves()
        httpClient.givenJsonResponse(
            BROWSE_PAGE_0_URL,
            """{"release-groups": [{"id": "$RELEASE_GROUP_MBID", "title": "F♯ A♯ ∞", "primary-type": "Album"}]}""",
        )
        givenSymbolTitleReleaseSearchHits()
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - MusicBrainz never saw the caller's spelling, so the name it returned is not the
        // name that was asked for and the route says so
        val success = result as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
    }

    @Test
    fun `the match is found on a later browse page when the catalogue runs past one page`() = runTest {
        // Given - a full first page of live bootleg groups, with the album only on the second
        givenCallerTitleSearchFindsNothing()
        givenArtistResolves()
        httpClient.givenJsonResponse(
            BROWSE_PAGE_0_URL,
            releaseGroupsJson((1..100).map { "2011-01-$it: Somewhere, Somewhere" }),
        )
        httpClient.givenJsonResponse(
            BROWSE_PAGE_1_URL,
            """{"release-groups": [{"id": "$RELEASE_GROUP_MBID", "title": "F♯ A♯ ∞", "primary-type": "Album"}]}""",
        )
        givenSymbolTitleReleaseSearchHits()
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - paging reached it
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        assertEquals(RELEASE_MBID, (result as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `a release outside the matched release group is never accepted`() = runTest {
        // Given - the browse matches, but the release search returns a same-titled release that
        // belongs to a different release group (score alone is not proof of identity)
        givenCallerTitleSearchFindsNothing()
        givenArtistResolves()
        httpClient.givenJsonResponse(
            BROWSE_PAGE_0_URL,
            """{"release-groups": [{"id": "$RELEASE_GROUP_MBID", "title": "F♯ A♯ ∞", "primary-type": "Album"}]}""",
        )
        httpClient.givenJsonResponse(
            SYMBOL_TITLE_SEARCH_URL,
            """
            {
              "releases": [{
                "id": "tribute-release",
                "score": 100,
                "title": "F♯ A♯ ∞",
                "artist-credit": [{"artist": {"id": "$ARTIST_MBID", "name": "Godspeed You! Black Emperor"}}],
                "release-group": {"id": "rg-some-other-group", "tags": [{"name": "post-rock", "count": 8}]}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - nothing is resolved
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `a populated pool that misses the score floor never reaches the fallback`() = runTest {
        // Given - MusicBrainz found releases for the title and none clears the floor, which means
        // the title is searchable and the album simply is not there
        httpClient.givenJsonResponse(
            CALLER_TITLE_SEARCH_URL,
            """
            {
              "count": 1,
              "releases": [{
                "id": "weak-hit",
                "score": 20,
                "title": "Something Else Entirely",
                "artist-credit": [{"artist": {"id": "$ARTIST_MBID", "name": "Godspeed You! Black Emperor"}}],
                "release-group": {"id": "rg-other"}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound, and no artist search or browse was spent on it
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.none { it.contains("artist?query") })
        assertTrue(httpClient.requestedUrls.none { it.contains("release-group?artist=") })
    }

    @Test
    fun `a near-miss artist stops the fallback before the browse`() = runTest {
        // Given - the artist search resolves to a similarly-named but different artist
        givenCallerTitleSearchFindsNothing()
        httpClient.givenJsonResponse(
            ARTIST_SEARCH_URL,
            """
            {
              "artists": [{
                "id": "some-other-artist",
                "score": 100,
                "name": "Godspeed You Black Emperor!",
                "tags": [{"name": "post-rock", "count": 8}]
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound, and the wrong artist's catalogue was never browsed
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.none { it.contains("release-group?artist=") })
    }

    @Test
    fun `a title that already matches exactly never reaches the fallback`() = runTest {
        // Given - the caller's own title search resolves directly
        httpClient.givenJsonResponse(
            "release%3A%22Yanqui+U.X.O.%22",
            """
            {
              "releases": [{
                "id": "yanqui-release",
                "score": 100,
                "title": "Yanqui U.X.O.",
                "artist-credit": [{"artist": {"id": "$ARTIST_MBID", "name": "Godspeed You! Black Emperor"}}],
                "release-group": {"id": "rg-yanqui", "tags": [{"name": "post-rock", "count": 8}]}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("Yanqui U.X.O.", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - resolved on the direct path, with no artist search and no browse behind it
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        assertEquals("yanqui-release", (result as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzId)
        assertTrue(httpClient.requestedUrls.none { it.contains("artist?query") })
        assertTrue(httpClient.requestedUrls.none { it.contains("release-group?artist=") })
    }

    @Test
    fun `an artist with no symbol-titled match still reports not found`() = runTest {
        // Given - the artist resolves and the browse is exhausted without a folded match
        givenCallerTitleSearchFindsNothing()
        givenArtistResolves()
        httpClient.givenJsonResponse(
            BROWSE_PAGE_0_URL,
            releaseGroupsJson(listOf("Yanqui U.X.O.", "Slow Riot for New Zero Kanada")),
        )
        val request = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound, with no suggestions to offer from an empty pool
        assertTrue("expected NotFound, got $result", result is EnrichmentResult.NotFound)
        assertNull((result as EnrichmentResult.NotFound).suggestions)
    }

    private companion object {
        const val ARTIST_MBID = "3648db01-b29d-4ab9-835c-83f6a5068fe4"
        const val RELEASE_GROUP_MBID = "01d06c6e-a4e6-3d8b-8a45-42a598fe87d7"
        const val RELEASE_MBID = "release-fa-infinity"
        const val CALLER_TITLE_SEARCH_URL = "release%3A%22F%23+A%23"
        const val ARTIST_SEARCH_URL = "artist?query=artist%3A%22Godspeed"
        const val BROWSE_PAGE_0_URL = "limit=100&offset=0"
        const val BROWSE_PAGE_1_URL = "limit=100&offset=100"

        /** The `♯` (`%E2%99%AF`) is what separates this search from the caller's own ASCII one. */
        const val SYMBOL_TITLE_SEARCH_URL = "release%3A%22F%E2%99%AF"
    }
}
