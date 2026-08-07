package com.landofoz.musicmeta.provider.musicbrainz

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A transient MusicBrainz failure must not masquerade as "no results".
 *
 * The bug this pins: every `MusicBrainzApi` method collapsed 429/5xx/network into an empty list,
 * indistinguishable from a genuine empty search. `enrichArtist` answers an empty search by running a
 * *fuzzy* search and returning `NotFound(suggestions = …)` — and at identity resolution a `NotFound`
 * carrying suggestions short-circuits the entire provider fan-out. A rate limit therefore produced a
 * flat refusal whose own top suggestion was a 100% match (observed live for `Portishead`).
 */
class MusicBrainzTransientFailureTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `rate-limited artist search is an Error, not a NotFound carrying a 100 percent suggestion`() = runTest {
        // Given — the strict search is rate limited, while the fuzzy search would happily answer
        // with a perfect match. This is the exact live shape: MusicBrainz scores Portishead 100.
        httpClient.givenHttpResult(STRICT_QUERY, HttpResult.RateLimited(retryAfterMs = 1000))
        httpClient.givenJsonResponse(FUZZY_QUERY, FUZZY_ARTISTS_TOP_SCORE_100)

        // When — resolving identity for the artist
        val result = provider.resolveIdentity(EnrichmentRequest.forArtist("Portishead"))

        // Then — a transient failure, classified as one
        assertTrue(
            "Expected Error, got ${result::class.simpleName}",
            result is EnrichmentResult.Error,
        )
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)

        // And — no suggestion list was invented from a search that never legitimately came back empty
        assertFalse(
            "Must not fall through to the fuzzy search after a transient failure",
            httpClient.requestedUrls.any { it.contains(FUZZY_QUERY) },
        )
    }

    @Test
    fun `server error on artist search is an Error, not a NotFound`() = runTest {
        // Given — MusicBrainz is 503ing
        httpClient.givenHttpResult(STRICT_QUERY, HttpResult.ServerError(503))

        // When — resolving identity
        val result = provider.resolveIdentity(EnrichmentRequest.forArtist("Portishead"))

        // Then — Error, so the circuit breaker records a failure rather than a healthy "no match"
        assertTrue(
            "Expected Error, got ${result::class.simpleName}",
            result is EnrichmentResult.Error,
        )
    }

    @Test
    fun `rate-limited album search is an Error, not a NotFound carrying suggestions`() = runTest {
        // Given — the same trap on the album identity path: enrichAlbum has the identical
        // isEmpty() -> fuzzy -> NotFound(suggestions) branch, and an identity NotFound carrying
        // suggestions short-circuits the fan-out for albums exactly as it does for artists.
        httpClient.givenHttpResult(STRICT_RELEASE_QUERY, HttpResult.RateLimited(retryAfterMs = 1000))
        httpClient.givenJsonResponse(FUZZY_RELEASE_QUERY, FUZZY_RELEASES_TOP_SCORE_100)

        // When — resolving identity for the album
        val result = provider.resolveIdentity(EnrichmentRequest.forAlbum("Dummy", "Portishead"))

        // Then — a transient failure, classified as one
        assertTrue(
            "Expected Error, got ${result::class.simpleName}",
            result is EnrichmentResult.Error,
        )
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertFalse(
            "Must not fall through to the fuzzy search after a transient failure",
            httpClient.requestedUrls.any { it.contains(FUZZY_RELEASE_QUERY) },
        )
    }

    @Test
    fun `rate-limited lookup is an Error, not a NotFound`() = runTest {
        // Given — an MBID is already known, so the search is skipped and lookupRelease runs.
        // The lookup half of MusicBrainzApi returns null rather than an empty list, so it collapsed
        // a transient into a plain NotFound: no bogus suggestions, but the breaker still scored a
        // rate-limited provider as healthy.
        httpClient.givenHttpResult(RELEASE_MBID, HttpResult.RateLimited(retryAfterMs = 1000))
        val request = EnrichmentRequest.forAlbum(
            "Dummy",
            "Portishead",
            identifiers = EnrichmentIdentifiers(musicBrainzId = RELEASE_MBID),
        )

        // When — enriching a type that resolves by direct lookup
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Error, so the chain records a breaker failure
        assertTrue(
            "Expected Error, got ${result::class.simpleName}",
            result is EnrichmentResult.Error,
        )
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `a genuinely empty search still returns NotFound with suggestions`() = runTest {
        // Given — a real 200 carrying zero artists, and a fuzzy search offering near misses.
        // This is the behaviour the fix must NOT disturb: an empty result is still an empty result.
        httpClient.givenJsonResponse(STRICT_QUERY, """{"artists":[]}""")
        httpClient.givenJsonResponse(FUZZY_QUERY, FUZZY_ARTISTS_NEAR_MISSES)

        // When — resolving identity
        val result = provider.resolveIdentity(EnrichmentRequest.forArtist("Portishead"))

        // Then — NotFound carrying the near misses, exactly as before
        assertTrue(
            "Expected NotFound, got ${result::class.simpleName}",
            result is EnrichmentResult.NotFound,
        )
        val suggestions = (result as EnrichmentResult.NotFound).suggestions
        assertEquals(listOf("Mortishead", "Portishell"), suggestions?.map { it.title })
    }

    @Test
    fun `a genuinely empty track search still returns NotFound with suggestions`() = runTest {
        // Given — a real 200 carrying zero recordings for a typo'd title (searchRecordings' own
        // hint-less retry only drops the release:"…" album term, it still re-sends the title
        // quoted, so it never rescues a typo), and a fuzzy (unquoted + Lucene ~) search offering
        // the near-miss. Mirrors the artist/album equivalents above.
        httpClient.givenJsonResponse(STRICT_TRACK_QUERY, """{"recordings":[]}""")
        httpClient.givenJsonResponse(FUZZY_TRACK_QUERY, FUZZY_RECORDINGS_NEAR_MISS)

        // When — resolving identity for the track
        val result = provider.resolveIdentity(EnrichmentRequest.forTrack("Enter Sandmanz Xyzqq", "Metallica"))

        // Then — NotFound carrying the near miss, so IdentityMatch.SUGGESTIONS becomes reachable
        assertTrue(
            "Expected NotFound, got ${result::class.simpleName}",
            result is EnrichmentResult.NotFound,
        )
        val suggestions = (result as EnrichmentResult.NotFound).suggestions
        assertEquals(listOf("Enter Sandman"), suggestions?.map { it.title })
    }

    companion object {
        /** `artist:"Portishead"` URL-encoded — the strict search. */
        private const val STRICT_QUERY = "artist%3A%22Portishead%22"

        /** `artist:Portishead~` URL-encoded — the fuzzy near-miss search. */
        private const val FUZZY_QUERY = "artist%3APortishead%7E"

        /** `release:"Dummy"` URL-encoded — the strict album search. */
        private const val STRICT_RELEASE_QUERY = "release%3A%22Dummy%22"

        /** `release:Dummy~` URL-encoded — the fuzzy album search. */
        private const val FUZZY_RELEASE_QUERY = "release%3ADummy%7E"

        private const val RELEASE_MBID = "3a3a1a7e-1111-2222-3333-444455556666"

        /** The live fuzzy response: the top hit is a perfect match, so refusing it is the defect. */
        private val FUZZY_ARTISTS_TOP_SCORE_100 = """
            {"artists":[
              {"id":"8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11","name":"Portishead","score":100,"type":"Group"},
              {"id":"11111111-1111-1111-1111-111111111111","name":"Mortishead","score":72,"type":"Group"}
            ]}
        """.trimIndent()

        /** Mirrors [FUZZY_ARTISTS_TOP_SCORE_100] for the album path: the top hit is perfect. */
        private val FUZZY_RELEASES_TOP_SCORE_100 = """
            {"releases":[
              {"id":"$RELEASE_MBID","title":"Dummy","score":100},
              {"id":"55555555-5555-5555-5555-555555555555","title":"Dummy Runs","score":71}
            ]}
        """.trimIndent()

        private val FUZZY_ARTISTS_NEAR_MISSES = """
            {"artists":[
              {"id":"11111111-1111-1111-1111-111111111111","name":"Mortishead","score":72,"type":"Group"},
              {"id":"22222222-2222-2222-2222-222222222222","name":"Portishell","score":68,"type":"Group"}
            ]}
        """.trimIndent()

        /** `recording:"Enter Sandmanz Xyzqq" AND artistname:"Metallica"` URL-encoded — the strict track search. */
        private const val STRICT_TRACK_QUERY = "recording%3A%22Enter+Sandmanz+Xyzqq%22"

        /** `recording:Enter Sandmanz Xyzqq~ AND artistname:Metallica~` URL-encoded — the fuzzy track search. */
        private const val FUZZY_TRACK_QUERY = "recording%3AEnter+Sandmanz+Xyzqq%7E"

        private val FUZZY_RECORDINGS_NEAR_MISS = """
            {"recordings":[
              {"id":"33333333-3333-3333-3333-333333333333","title":"Enter Sandman","score":68}
            ]}
        """.trimIndent()
    }
}
