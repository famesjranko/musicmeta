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
        rateLimit("api.deezer.com")
        val provider = DeezerProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `deezer album search server error is an Error`() = runTest {
        http.givenHttpResult("api.deezer.com", HttpResult.ServerError(503))
        val provider = DeezerProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_ART))
    }

    // --- iTunes -------------------------------------------------------------------------------

    @Test
    fun `itunes album search rate limited is an Error`() = runTest {
        rateLimit("itunes.apple.com")
        val provider = ITunesProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_ART))
    }

    @Test
    fun `itunes artist lookup network drop is an Error`() = runTest {
        http.givenHttpResult("itunes.apple.com", HttpResult.NetworkError("connection reset"))
        val provider = ITunesProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_DISCOGRAPHY))
    }

    // --- Last.fm ------------------------------------------------------------------------------

    @Test
    fun `lastfm artist info rate limited is an Error`() = runTest {
        rateLimit("audioscrobbler.com")
        val provider = LastFmProvider("key", http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `lastfm album info server error is an Error`() = runTest {
        http.givenHttpResult("audioscrobbler.com", HttpResult.ServerError(500))
        val provider = LastFmProvider("key", http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_METADATA))
    }

    /** A 401 must stay AUTH: the transient classification is added alongside it, not over it. */
    @Test
    fun `lastfm rejected key is still an AUTH Error`() = runTest {
        http.givenHttpResult("audioscrobbler.com", HttpResult.ClientError(401))
        val provider = LastFmProvider("key", http, RateLimiter(0))

        val result = provider.enrich(ARTIST, EnrichmentType.ARTIST_BIO)

        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.AUTH, (result as EnrichmentResult.Error).errorKind)
    }

    /** A genuine 404 is still "no such thing" — the behaviour the fix must not disturb. */
    @Test
    fun `lastfm 404 is still NotFound, not an Error`() = runTest {
        http.givenHttpResult("audioscrobbler.com", HttpResult.ClientError(404))
        val provider = LastFmProvider("key", http, RateLimiter(0))

        val result = provider.enrich(ARTIST, EnrichmentType.ARTIST_BIO)

        assertTrue("Expected NotFound, got ${result::class.simpleName}", result is EnrichmentResult.NotFound)
    }

    // --- Discogs ------------------------------------------------------------------------------

    @Test
    fun `discogs artist search rate limited is an Error`() = runTest {
        rateLimit("api.discogs.com")
        val provider = DiscogsProvider("token", http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `discogs release search server error is an Error`() = runTest {
        http.givenHttpResult("api.discogs.com", HttpResult.ServerError(502))
        val provider = DiscogsProvider("token", http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM, EnrichmentType.ALBUM_METADATA))
    }

    // --- Fanart.tv ----------------------------------------------------------------------------

    @Test
    fun `fanarttv artist images rate limited is an Error`() = runTest {
        rateLimit("fanart.tv")
        val provider = FanartTvProvider("key", http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `fanarttv album images server error is an Error`() = runTest {
        http.givenHttpResult("fanart.tv", HttpResult.ServerError(503))
        val provider = FanartTvProvider("key", http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_ART))
    }

    // --- ListenBrainz -------------------------------------------------------------------------

    /** GET returning a JSON *array* — the shape that used `bodyOrNull()`. */
    @Test
    fun `listenbrainz top recordings rate limited is an Error`() = runTest {
        rateLimit("api.listenbrainz.org")
        val provider = ListenBrainzProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_TOP_TRACKS))
    }

    /** POST returning a JSON array — a different `HttpClient` method, same collapse. */
    @Test
    fun `listenbrainz batch popularity POST server error is an Error`() = runTest {
        http.givenHttpResultArray("api.listenbrainz.org", HttpResult.ServerError(503))
        val provider = ListenBrainzProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_POPULARITY))
    }

    /** The one ListenBrainz call carrying a token — it kept auth classification and gained transient. */
    @Test
    fun `listenbrainz radio rate limited is an Error`() = runTest {
        rateLimit("api.listenbrainz.org")
        val provider = ListenBrainzProvider(http, RateLimiter(0), authToken = "token")

        assertNetworkError(provider.enrich(ARTIST_WITH_MBID, EnrichmentType.ARTIST_RADIO_DISCOVERY))
    }

    // --- Cover Art Archive --------------------------------------------------------------------

    /**
     * The metadata endpoint (`/release/{mbid}`), the one Cover Art Archive call that returns an
     * `HttpResult`. The `front-` availability checks go through `fetchRedirectUrl`, which returns
     * `String?` and cannot carry a status — exempted, see the PR survey.
     */
    @Test
    fun `coverartarchive metadata rate limited is an Error`() = runTest {
        rateLimit("coverartarchive.org")
        val provider = CoverArtArchiveProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_ART_BACK))
    }

    @Test
    fun `coverartarchive metadata server error is an Error`() = runTest {
        http.givenHttpResult("coverartarchive.org", HttpResult.ServerError(503))
        val provider = CoverArtArchiveProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ALBUM_WITH_RELEASE_GROUP, EnrichmentType.ALBUM_BOOKLET))
    }

    // --- LRCLIB -------------------------------------------------------------------------------

    /** The exact-match GET returning a JSON object. */
    @Test
    fun `lrclib exact lookup rate limited is an Error`() = runTest {
        rateLimit("lrclib.net")
        val provider = LrcLibProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(TRACK, EnrichmentType.LYRICS_SYNCED))
    }

    /** The search fallback returning a JSON *array* — the second call shape in `LrcLibApi`. */
    @Test
    fun `lrclib search server error is an Error`() = runTest {
        http.givenHttpResult("/api/get", HttpResult.ClientError(404))
        http.givenHttpResultArray("/api/search", HttpResult.ServerError(500))
        val provider = LrcLibProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(TRACK, EnrichmentType.LYRICS_SYNCED))
    }

    // --- Wikidata -----------------------------------------------------------------------------

    @Test
    fun `wikidata claims rate limited is an Error`() = runTest {
        rateLimit("wikidata.org")
        val provider = WikidataProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIDATA_ID, EnrichmentType.ARTIST_PHOTO))
    }

    @Test
    fun `wikidata claims network drop is an Error`() = runTest {
        http.givenHttpResult("wikidata.org", HttpResult.NetworkError("connection reset"))
        val provider = WikidataProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIDATA_ID, EnrichmentType.COUNTRY))
    }

    // --- Wikipedia ----------------------------------------------------------------------------

    @Test
    fun `wikipedia summary rate limited is an Error`() = runTest {
        rateLimit("wikipedia.org")
        val provider = WikipediaProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIPEDIA_TITLE, EnrichmentType.ARTIST_BIO))
    }

    @Test
    fun `wikipedia media list server error is an Error`() = runTest {
        http.givenHttpResult("wikipedia.org", HttpResult.ServerError(503))
        val provider = WikipediaProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIPEDIA_TITLE, EnrichmentType.ARTIST_PHOTO))
    }

    /**
     * The seventh site: `WikipediaProvider.resolveFromWikidata` calls `wikidata.org` itself rather
     * than through `WikidataApi`, so an API-class-keyed survey misses it.
     */
    @Test
    fun `wikipedia title resolution via wikidata rate limited is an Error`() = runTest {
        rateLimit("wikidata.org")
        val provider = WikipediaProvider(http, RateLimiter(0))

        assertNetworkError(provider.enrich(ARTIST_WITH_WIKIDATA_ID, EnrichmentType.ARTIST_BIO))
    }

    private companion object {
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
