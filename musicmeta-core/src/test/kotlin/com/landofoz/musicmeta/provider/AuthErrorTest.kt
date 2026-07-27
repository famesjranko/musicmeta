package com.landofoz.musicmeta.provider

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.engine.ProviderChain
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.fanarttv.FanartTvProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A rejected key must surface as `Error(AUTH)`, not `NotFound`. `NotFound` records a circuit
 * breaker *success* (`docs/pitfalls.md` §4), so the old behaviour left a provider with a bad
 * token reporting healthy and returning nothing forever.
 */
class AuthErrorTest {

    private val mbid = "a74b1b7f-71a5-4011-9441-d0b5e4122711"

    private fun http(urlContains: String, status: Int) = FakeHttpClient().apply {
        givenHttpResult(urlContains, HttpResult.ClientError(status))
        givenHttpResultArray(urlContains, HttpResult.ClientError(status))
    }

    private fun lastFm(status: Int = 401) =
        LastFmProvider("k", http("audioscrobbler", status), RateLimiter(0L)) to
            EnrichmentRequest.forArtist(name = "Radiohead")

    private fun discogs(status: Int = 401) =
        DiscogsProvider("t", http("discogs.com", status), RateLimiter(0L)) to
            EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

    private fun fanartTv(status: Int = 401) =
        FanartTvProvider("k", http("fanart.tv", status), RateLimiter(0L)) to
            EnrichmentRequest.forArtist(name = "Radiohead", identifiers = EnrichmentIdentifiers(musicBrainzId = mbid))

    /**
     * Only ListenBrainz's radio endpoint sends the token. The popularity endpoints are public and
     * the provider has `requiresApiKey = false`, so a 403 there is not a key the consumer can fix.
     */
    private fun listenBrainz(status: Int = 401) =
        ListenBrainzProvider(http("listenbrainz.org", status), RateLimiter(0L), authToken = "t") to
            EnrichmentRequest.forArtist(name = "Radiohead", identifiers = EnrichmentIdentifiers(musicBrainzId = mbid))

    private suspend fun assertAuthError(pair: Pair<EnrichmentProvider, EnrichmentRequest>, type: EnrichmentType) {
        val (provider, request) = pair
        val result = provider.enrich(request, type)
        assertEquals("${provider.id} should report AUTH", ErrorKind.AUTH, (result as? EnrichmentResult.Error)?.errorKind)
    }

    private suspend fun assertNotFound(pair: Pair<EnrichmentProvider, EnrichmentRequest>, type: EnrichmentType) {
        val (provider, request) = pair
        assertEquals(EnrichmentResult.NotFound(type, provider.id), provider.enrich(request, type))
    }

    @Test fun `401 is an AUTH error, not NotFound`() = runTest {
        assertAuthError(lastFm(), EnrichmentType.SIMILAR_ARTISTS)
        assertAuthError(discogs(), EnrichmentType.ALBUM_ART)
        assertAuthError(fanartTv(), EnrichmentType.ARTIST_PHOTO)
        assertAuthError(listenBrainz(), EnrichmentType.ARTIST_RADIO_DISCOVERY)
    }

    @Test fun `403 is an AUTH error too`() = runTest {
        assertAuthError(lastFm(403), EnrichmentType.SIMILAR_ARTISTS)
        assertAuthError(discogs(403), EnrichmentType.ALBUM_ART)
        assertAuthError(fanartTv(403), EnrichmentType.ARTIST_PHOTO)
        assertAuthError(listenBrainz(403), EnrichmentType.ARTIST_RADIO_DISCOVERY)
    }

    @Test fun `other 4xx responses stay NotFound`() = runTest {
        assertNotFound(lastFm(404), EnrichmentType.SIMILAR_ARTISTS)
        assertNotFound(discogs(404), EnrichmentType.ALBUM_ART)
        assertNotFound(fanartTv(404), EnrichmentType.ARTIST_PHOTO)
        assertNotFound(listenBrainz(404), EnrichmentType.ARTIST_RADIO_DISCOVERY)
    }

    @Test fun `a 403 from a public ListenBrainz endpoint is not an auth failure`() = runTest {
        // Given — popularity sends no token, so a 403 there is nothing the consumer can fix
        assertNotFound(listenBrainz(403), EnrichmentType.ARTIST_POPULARITY)
        assertNotFound(listenBrainz(401), EnrichmentType.SIMILAR_ARTISTS)
    }

    @Test fun `the breaker records a failure on a 401, not a success`() = runTest {
        // Given — a chain whose breaker opens on a single failure
        val (provider, request) = discogs()
        val breaker = CircuitBreaker(failureThreshold = 1)
        val chain = ProviderChain(EnrichmentType.ALBUM_ART, listOf(provider), mapOf(provider.id to breaker))

        // When — one 401
        chain.resolve(request)

        // Then — the breaker opened. A NotFound would have recorded a success and left it closed.
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertFalse(breaker.allowRequest())
    }
}
