package com.landofoz.musicmeta.provider

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.fanarttv.FanartTvProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A 429, a 5xx or a dropped connection must reach the consumer as `Error(ErrorKind.NETWORK)`, never
 * as an empty result — for every provider, not just MusicBrainz (`MusicBrainzTransientFailureTest`).
 *
 * The cost of collapsing them is breaker health (`docs/pitfalls.md` §4): a rate-limited provider
 * answering `NotFound` to everything records breaker *successes*, so it looks healthy while a
 * lower-priority fallback quietly wins every type, and `STALE_IF_ERROR` never engages because no
 * `Error` is ever recorded.
 *
 * Each case stubs the provider's whole host as transient, so it pins the classification rather than
 * one URL. Two types per provider where the API has two distinct call shapes.
 */
class ProviderTransientFailureTest {

    private lateinit var http: FakeHttpClient

    @Before
    fun setUp() {
        http = FakeHttpClient()
    }

    /** Every request to [host] is rate limited. */
    private fun rateLimit(host: String) {
        http.givenHttpResult(host, HttpResult.RateLimited(retryAfterMs = 1000))
        http.givenHttpResultArray(host, HttpResult.RateLimited(retryAfterMs = 1000))
        http.givenRedirectResult(host, HttpResult.RateLimited(retryAfterMs = 1000))
    }

    private fun assertNetworkError(result: EnrichmentResult) {
        assertTrue(
            "Expected Error, got ${result::class.simpleName}",
            result is EnrichmentResult.Error,
        )
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    // --- Deezer -------------------------------------------------------------------------------

    @Test
    fun `deezer artist search rate limited is an Error`() = runTest {
        // Given — Deezer's host answering every request as rate limited
        rateLimit("api.deezer.com")
        val provider = DeezerProvider(http, RateLimiter(0))

        // When — enriching an artist photo
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `deezer album search server error is an Error`() = runTest {
        // Given — Deezer's host returning a 503
        http.givenHttpResult("api.deezer.com", HttpResult.ServerError(503))
        val provider = DeezerProvider(http, RateLimiter(0))

        // When — enriching album art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_ART))
    }

    /**
     * Deezer answers a quota rejection with **HTTP 200** and an `error` object in place of the
     * payload, so no HTTP-layer classification can see it (`DeezerApi.fetchJson`). `code: 4` is
     * community-corroborated, not an official constant — Deezer's error table is login-gated.
     */
    @Test
    fun `deezer 200 quota body is an Error`() = runTest {
        // Given — Deezer's HTTP-200 quota-rejection envelope in place of the payload
        http.givenJsonResponse("api.deezer.com", QUOTA_ENVELOPE)
        val provider = DeezerProvider(http, RateLimiter(0))

        // When — enriching an artist photo
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_PHOTO))
    }

    /**
     * The other direction, load-bearing: a `DataException` envelope is a genuine "no such thing"
     * (observed live for a missing artist) and must stay `NotFound`, not become an `Error`.
     */
    @Test
    fun `deezer 200 no-data body is still NotFound, not an Error`() = runTest {
        // Given — Deezer's HTTP-200 "no such artist" DataException envelope
        http.givenJsonResponse("api.deezer.com", NO_DATA_ENVELOPE)
        val provider = DeezerProvider(http, RateLimiter(0))

        // When — enriching an artist photo
        val result = provider.enrich(ARTIST, EnrichmentType.ARTIST_PHOTO)

        // Then — the result stays NotFound, not an Error
        assertTrue("Expected NotFound, got ${result::class.simpleName}", result is EnrichmentResult.NotFound)
    }

    // --- iTunes -------------------------------------------------------------------------------

    /**
     * iTunes throttles with 403, not 429 — undocumented by Apple except on its own developer
     * forums (thread 66399). The classification is iTunes-local: these endpoints send no
     * credentials, so a 403 here cannot be a rejected key.
     */
    @Test
    fun `itunes 403 is an Error, not NotFound`() = runTest {
        // Given — iTunes' host returning a 403, its throttling response
        http.givenHttpResult("itunes.apple.com", HttpResult.ClientError(403))
        val provider = ITunesProvider(http, RateLimiter(0))

        // When — enriching album art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_ART))
    }

    /** A genuine 404 is still "no such thing" — the behaviour the 403 reading must not disturb. */
    @Test
    fun `itunes 404 is still NotFound, not an Error`() = runTest {
        // Given — iTunes' host returning a genuine 404
        http.givenHttpResult("itunes.apple.com", HttpResult.ClientError(404))
        val provider = ITunesProvider(http, RateLimiter(0))

        // When — enriching album art
        val result = provider.enrich(ALBUM, EnrichmentType.ALBUM_ART)

        // Then — the result stays NotFound, not an Error
        assertTrue("Expected NotFound, got ${result::class.simpleName}", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `itunes album search rate limited is an Error`() = runTest {
        // Given — iTunes' host answering every request as rate limited
        rateLimit("itunes.apple.com")
        val provider = ITunesProvider(http, RateLimiter(0))

        // When — enriching album art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_ART))
    }

    @Test
    fun `itunes artist lookup network drop is an Error`() = runTest {
        // Given — iTunes' host dropping the connection
        http.givenHttpResult("itunes.apple.com", HttpResult.NetworkError("connection reset"))
        val provider = ITunesProvider(http, RateLimiter(0))

        // When — enriching an artist's discography
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_DISCOGRAPHY))
    }

    // --- Last.fm ------------------------------------------------------------------------------

    @Test
    fun `lastfm artist info rate limited is an Error`() = runTest {
        // Given — Last.fm's host answering every request as rate limited
        rateLimit("audioscrobbler.com")
        val provider = LastFmProvider("key", http, RateLimiter(0))

        // When — enriching an artist bio
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `lastfm album info server error is an Error`() = runTest {
        // Given — Last.fm's host returning a 500
        http.givenHttpResult("audioscrobbler.com", HttpResult.ServerError(500))
        val provider = LastFmProvider("key", http, RateLimiter(0))

        // When — enriching album metadata
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_METADATA))
    }

    /** A 401 must stay AUTH: the transient classification is added alongside it, not over it. */
    @Test
    fun `lastfm rejected key is still an AUTH Error`() = runTest {
        // Given — Last.fm's host rejecting the API key with a 401
        http.givenHttpResult("audioscrobbler.com", HttpResult.ClientError(401))
        val provider = LastFmProvider("key", http, RateLimiter(0))

        // When — enriching an artist bio
        val result = provider.enrich(ARTIST, EnrichmentType.ARTIST_BIO)

        // Then — the result stays an AUTH Error, not NETWORK
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.AUTH, (result as EnrichmentResult.Error).errorKind)
    }

    /** A genuine 404 is still "no such thing" — the behaviour the fix must not disturb. */
    @Test
    fun `lastfm 404 is still NotFound, not an Error`() = runTest {
        // Given — Last.fm's host returning a genuine 404
        http.givenHttpResult("audioscrobbler.com", HttpResult.ClientError(404))
        val provider = LastFmProvider("key", http, RateLimiter(0))

        // When — enriching an artist bio
        val result = provider.enrich(ARTIST, EnrichmentType.ARTIST_BIO)

        // Then — the result stays NotFound, not an Error
        assertTrue("Expected NotFound, got ${result::class.simpleName}", result is EnrichmentResult.NotFound)
    }

    // --- Discogs ------------------------------------------------------------------------------

    @Test
    fun `discogs artist search rate limited is an Error`() = runTest {
        // Given — Discogs' host answering every request as rate limited
        rateLimit("api.discogs.com")
        val provider = DiscogsProvider("token", http, RateLimiter(0))

        // When — enriching an artist photo
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `discogs release search server error is an Error`() = runTest {
        // Given — Discogs' host returning a 502
        http.givenHttpResult("api.discogs.com", HttpResult.ServerError(502))
        val provider = DiscogsProvider("token", http, RateLimiter(0))

        // When — enriching album metadata
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_METADATA))
    }

    // --- Fanart.tv ----------------------------------------------------------------------------

    @Test
    fun `fanarttv artist images rate limited is an Error`() = runTest {
        // Given — fanart.tv's host answering every request as rate limited
        rateLimit("fanart.tv")
        val provider = FanartTvProvider("key", http, RateLimiter(0))

        // When — enriching an artist photo
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `fanarttv album images server error is an Error`() = runTest {
        // Given — fanart.tv's host returning a 503
        http.givenHttpResult("fanart.tv", HttpResult.ServerError(503))
        val provider = FanartTvProvider("key", http, RateLimiter(0))

        // When — enriching album art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_ART))
    }

    // --- ListenBrainz -------------------------------------------------------------------------

    /** GET returning a JSON *array* — the shape that used `bodyOrNull()`. */
    @Test
    fun `listenbrainz top recordings rate limited is an Error`() = runTest {
        // Given — ListenBrainz's host answering every request as rate limited
        rateLimit("api.listenbrainz.org")
        val provider = ListenBrainzProvider(http, RateLimiter(0))

        // When — enriching an artist's top tracks
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_TOP_TRACKS))
    }

    /** POST returning a JSON array — a different `HttpClient` method, same collapse. */
    @Test
    fun `listenbrainz batch popularity POST server error is an Error`() = runTest {
        // Given — ListenBrainz's host returning a 503 for the POST call shape
        http.givenHttpResultArray("api.listenbrainz.org", HttpResult.ServerError(503))
        val provider = ListenBrainzProvider(http, RateLimiter(0))

        // When — enriching an artist's popularity
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_POPULARITY))
    }

    /** The one ListenBrainz call carrying a token — it kept auth classification and gained transient. */
    @Test
    fun `listenbrainz radio rate limited is an Error`() = runTest {
        // Given — ListenBrainz's host answering every request as rate limited, with an auth token set
        rateLimit("api.listenbrainz.org")
        val provider = ListenBrainzProvider(http, RateLimiter(0), authToken = "token")

        // When — enriching an artist's radio discovery
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_RADIO_DISCOVERY))
    }

    // --- Cover Art Archive --------------------------------------------------------------------

    /** The metadata endpoint (`/release/{mbid}`), reached by the metadata-driven image types. */
    @Test
    fun `coverartarchive metadata rate limited is an Error`() = runTest {
        // Given — Cover Art Archive's host answering every request as rate limited
        rateLimit("coverartarchive.org")
        val provider = CoverArtArchiveProvider(http, RateLimiter(0))

        // When — enriching the back cover art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_ART_BACK))
    }

    @Test
    fun `coverartarchive metadata server error is an Error`() = runTest {
        // Given — Cover Art Archive's host returning a 503
        http.givenHttpResult("coverartarchive.org", HttpResult.ServerError(503))
        val provider = CoverArtArchiveProvider(http, RateLimiter(0))

        // When — enriching the album booklet
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_BOOKLET))
    }

    /**
     * The `front-{size}` redirect that resolves `ALBUM_ART`, Cover Art Archive's top-priority
     * capability — a `String?` path until now, so a 429 here was indistinguishable from no artwork.
     *
     * Only the redirect is transient here, deliberately: `rateLimit()` would rate-limit the
     * decorative metadata call too, and the `Error` would arrive from there even with the redirect
     * still collapsing.
     */
    @Test
    fun `coverartarchive release artwork redirect rate limited is an Error`() = runTest {
        // Given — a healthy metadata call, but the artwork redirect itself rate limited
        http.givenHttpResult("coverartarchive.org", HttpResult.ClientError(404))
        http.givenRedirectResult("coverartarchive.org", HttpResult.RateLimited(retryAfterMs = 1000))
        val provider = CoverArtArchiveProvider(http, RateLimiter(0))

        // When — enriching album art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_ART))
    }

    /** The release-group fallback redirect — the second call shape on the same path. */
    @Test
    fun `coverartarchive release-group artwork redirect server error is an Error`() = runTest {
        // Given — the release redirect missing and the release-group fallback redirect returning a 503
        http.givenRedirectResult("/release/", HttpResult.ClientError(404))
        http.givenRedirectResult("/release-group/", HttpResult.ServerError(503))
        val provider = CoverArtArchiveProvider(http, RateLimiter(0))

        // When — enriching album art
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_ART))
    }

    // --- LRCLIB -------------------------------------------------------------------------------

    /** The exact-match GET returning a JSON object. */
    @Test
    fun `lrclib exact lookup rate limited is an Error`() = runTest {
        // Given — LRCLIB's host answering every request as rate limited
        rateLimit("lrclib.net")
        val provider = LrcLibProvider(http, RateLimiter(0))

        // When — enriching synced lyrics
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(TRACK, EnrichmentType.LYRICS_SYNCED))
    }

    /** The search fallback returning a JSON *array* — the second call shape in `LrcLibApi`. */
    @Test
    fun `lrclib search server error is an Error`() = runTest {
        // Given — the exact-match lookup missing and the search fallback returning a 500
        http.givenHttpResult("/api/get", HttpResult.ClientError(404))
        http.givenHttpResultArray("/api/search", HttpResult.ServerError(500))
        val provider = LrcLibProvider(http, RateLimiter(0))

        // When — enriching synced lyrics
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(TRACK, EnrichmentType.LYRICS_SYNCED))
    }

    // --- Wikidata -----------------------------------------------------------------------------

    @Test
    fun `wikidata claims rate limited is an Error`() = runTest {
        // Given — Wikidata's host answering every request as rate limited
        rateLimit("wikidata.org")
        val provider = WikidataProvider(http, RateLimiter(0))

        // When — enriching an artist photo
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIDATA_ID, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `wikidata claims network drop is an Error`() = runTest {
        // Given — Wikidata's host dropping the connection
        http.givenHttpResult("wikidata.org", HttpResult.NetworkError("connection reset"))
        val provider = WikidataProvider(http, RateLimiter(0))

        // When — enriching the artist's country
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIDATA_ID, EnrichmentType.COUNTRY))
    }

    // --- Wikipedia ----------------------------------------------------------------------------

    @Test
    fun `wikipedia summary rate limited is an Error`() = runTest {
        // Given — Wikipedia's host answering every request as rate limited
        rateLimit("wikipedia.org")
        val provider = WikipediaProvider(http, RateLimiter(0))

        // When — enriching an artist bio
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIPEDIA_TITLE, EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `wikipedia media list server error is an Error`() = runTest {
        // Given — Wikipedia's host returning a 503
        http.givenHttpResult("wikipedia.org", HttpResult.ServerError(503))
        val provider = WikipediaProvider(http, RateLimiter(0))

        // When — enriching an artist photo
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIPEDIA_TITLE, EnrichmentType.ARTIST_PHOTO))
    }

    /**
     * The seventh site: `WikipediaProvider.resolveFromWikidata` calls `wikidata.org` itself rather
     * than through `WikidataApi`, so an API-class-keyed survey misses it.
     */
    @Test
    fun `wikipedia title resolution via wikidata rate limited is an Error`() = runTest {
        // Given — Wikidata's host answering every request as rate limited, reached via resolveFromWikidata
        rateLimit("wikidata.org")
        val provider = WikipediaProvider(http, RateLimiter(0))

        // When — enriching an artist bio for a request with only a Wikidata id
        // Then — the result is a NETWORK Error, not an empty result
        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIDATA_ID, EnrichmentType.ARTIST_BIO))
    }

    private companion object {
        /** Deezer's quota rejection, served with HTTP 200. Community-corroborated shape. */
        const val QUOTA_ENVELOPE = """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}"""

        /** Observed live for `GET /artist/999999999999` — a genuine "no such thing". */
        const val NO_DATA_ENVELOPE = """{"error":{"type":"DataException","message":"no data","code":800}}"""

        private const val MBID = "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11"
        private const val RELEASE_GROUP_MBID = "3a3a1a7e-1111-2222-3333-444455556666"

        val ARTIST = EnrichmentRequest.forArtist("Portishead")
        val TRACK = EnrichmentRequest.forTrack("Glory Box", "Portishead")
        val ARTIST_WITH_WIKIDATA_ID = EnrichmentRequest.forArtist(
            "Portishead",
            identifiers = EnrichmentIdentifiers(wikidataId = "Q189599"),
        )
        val ARTIST_WITH_WIKIPEDIA_TITLE = EnrichmentRequest.forArtist(
            "Portishead",
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Portishead (band)"),
        )
        val ALBUM = EnrichmentRequest.forAlbum("Dummy", "Portishead")
        val ARTIST_WITH_MBID = EnrichmentRequest.forArtist("Portishead", mbid = MBID)
        val ALBUM_WITH_RELEASE_GROUP = EnrichmentRequest.forAlbum(
            "Dummy",
            "Portishead",
            identifiers = EnrichmentIdentifiers(
                musicBrainzId = MBID,
                musicBrainzReleaseGroupId = RELEASE_GROUP_MBID,
            ),
        )
    }
}
