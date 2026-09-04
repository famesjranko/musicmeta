package com.landofoz.musicmeta.provider

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.ResolvedEntityNames
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A request naming an entity in its own script reaches a romanizing provider, whose candidates carry
 * the romanized name only. The alias pool identity resolution produced is what lets those be
 * verified rather than rejected — and what keeps a candidate no known name form matches rejected.
 */
class NonLatinAliasPoolMatchingTest {

    private val httpClient = FakeHttpClient()

    @Test
    fun `deezer artist photo matches a romanized candidate through the alias pool`() = runTest {
        // Given - a request in Japanese, and the pool identity resolution holds for that artist
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_ARTIST_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - Deezer answers under the romanized name only
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - the candidate is verified against the pool, at the primary-alias tier
        assertTrue("expected a match through the pool, got $result", result is EnrichmentResult.Success)
        assertEquals(PRIMARY_ALIAS_CONFIDENCE, (result as EnrichmentResult.Success).confidence, 0.0001f)
    }

    @Test
    fun `itunes discography matches a romanized candidate through the alias pool`() = runTest {
        // Given - a request in Japanese, and iTunes holding the artist under its romanization
        httpClient.givenJsonResponse("itunes.apple.com/search", ITUNES_ARTIST_SEARCH)
        httpClient.givenJsonResponse("itunes.apple.com/lookup", ITUNES_ARTIST_ALBUMS)
        val provider = ITunesProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - the discography is asked for
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY) }

        // Then - the romanized artist is accepted, at the primary-alias tier
        assertTrue("expected a match through the pool, got $result", result is EnrichmentResult.Success)
        assertEquals(PRIMARY_ALIAS_CONFIDENCE, (result as EnrichmentResult.Success).confidence, 0.0001f)
    }

    @Test
    fun `discogs artist photo matches a romanized candidate through the alias pool`() = runTest {
        // Given - a request in Japanese, and Discogs holding the artist under its romanization
        httpClient.givenJsonResponse("api.discogs.com/database/search", DISCOGS_ARTIST_SEARCH)
        httpClient.givenJsonResponse("api.discogs.com/artists", DISCOGS_ARTIST_DETAIL)
        val provider = DiscogsProvider("token", httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - the artist photo is asked for
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - both the search hit and the detail record are accepted through the pool
        assertTrue("expected a match through the pool, got $result", result is EnrichmentResult.Success)
    }

    @Test
    fun `a latin exact match keeps the confidence it has today`() = runTest {
        // Given - a Latin-script request whose candidate carries the requested name itself
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_LATIN_ARTIST_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - a pool is present but the requested name already matches
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - the confidence is the unscaled one this path has always reported
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(EXACT_CONFIDENCE, (result as EnrichmentResult.Success).confidence, 0.0001f)
    }

    @Test
    fun `a candidate matching no name in the pool is still rejected`() = runTest {
        // Given - a Japanese request and a provider answering with an unrelated artist
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_UNRELATED_ARTIST_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - the pool is consulted for the unrelated candidate
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - the reject path is unchanged: no known name form matches
        assertTrue("expected a reject, got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `an alias is accepted only as the same name, never by containment`() = runTest {
        // Given - a candidate whose name merely contains an alias
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_CONTAINING_ARTIST_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - the pool is consulted
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - a pool multiplies partial matches, so only same-name acceptance is granted
        assertTrue("expected a reject, got $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `the pool is resolved once however many candidates consult it`() = runTest {
        // Given - a pool whose source counts how often it is asked
        var calls = 0
        val names = ResolvedEntityNames()
        names.offerAliases {
            calls++
            TOKYO_JIHEN_POOL
        }

        // When - two matchers consult it in one call
        val first = withContext(names) { names.aliases() }
        val second = withContext(names) { names.aliases() }

        // Then - the source ran once and both readers saw the same pool
        assertEquals(1, calls)
        assertEquals(first, second)
    }

    private suspend fun <T> withPool(body: suspend () -> T): T {
        val names = ResolvedEntityNames()
        names.offerAliases { TOKYO_JIHEN_POOL }
        return withContext(names) { body() }
    }

    private companion object {
        /** `fuzzyMatch(hasArtistMatch = true)`, the value every artist-search path reports today. */
        const val EXACT_CONFIDENCE = 0.8f

        /** [EXACT_CONFIDENCE] scaled by `NameMatchTier.PRIMARY_ALIAS`. */
        const val PRIMARY_ALIAS_CONFIDENCE = 0.8f * 0.95f

        val TOKYO_JIHEN_POOL = listOf(
            AlternativeName("東京事変", official = true),
            AlternativeName("Tokyo Jihen", official = true),
            AlternativeName("Tokyo Incidents", official = true),
            AlternativeName("東京事變", official = false),
        )

        // https://api.deezer.com/search/artist?q=東京事変&limit=10 — captured 2026-09-05, first two hits.
        const val DEEZER_ARTIST_SEARCH = """
        {"data":[
          {"id":384298,"name":"Tokyo Incidents","link":"https://www.deezer.com/artist/384298",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/f05/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/f05/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/f05/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/f05/1000x1000.jpg",
           "nb_album":45,"nb_fan":2487,"radio":true,"type":"artist"},
          {"id":57460332,"name":"Touhou Jihen","link":"https://www.deezer.com/artist/57460332",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/aaa/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/aaa/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/aaa/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/aaa/1000x1000.jpg",
           "nb_album":9,"nb_fan":94,"radio":true,"type":"artist"}
        ],"total":2}
        """

        // Same endpoint, with the pool's only hit replaced by an artist no name form names.
        const val DEEZER_UNRELATED_ARTIST_SEARCH = """
        {"data":[
          {"id":399,"name":"Radiohead","link":"https://www.deezer.com/artist/399",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/rh/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/rh/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/rh/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/rh/1000x1000.jpg",
           "nb_album":72,"nb_fan":5000000,"radio":true,"type":"artist"}
        ],"total":1}
        """

        // Same endpoint, with a candidate whose name contains an alias without being it.
        const val DEEZER_CONTAINING_ARTIST_SEARCH = """
        {"data":[
          {"id":90001,"name":"Tokyo Jihen Tribute Band","link":"https://www.deezer.com/artist/90001",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/tb/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/tb/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/tb/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/tb/1000x1000.jpg",
           "nb_album":1,"nb_fan":12,"radio":true,"type":"artist"}
        ],"total":1}
        """

        // https://api.deezer.com/search/artist?q=Radiohead&limit=10 — the shape above, Latin request.
        const val DEEZER_LATIN_ARTIST_SEARCH = DEEZER_UNRELATED_ARTIST_SEARCH

        // https://itunes.apple.com/search?media=music&entity=musicArtist&term=東京事変&limit=10
        // — captured 2026-09-05, verbatim.
        const val ITUNES_ARTIST_SEARCH = """
        {"resultCount":1,"results":[
          {"wrapperType":"artist","artistType":"Artist","artistName":"Tokyo Incidents",
           "artistLinkUrl":"https://music.apple.com/us/artist/tokyo-incidents/74570585?uo=4",
           "artistId":74570585,"amgArtistId":879391,"primaryGenreName":"Rock","primaryGenreId":21}]}
        """

        const val ITUNES_ARTIST_ALBUMS = """
        {"resultCount":2,"results":[
          {"wrapperType":"artist","artistType":"Artist","artistName":"Tokyo Incidents",
           "artistId":74570585,"primaryGenreName":"Rock"},
          {"wrapperType":"collection","collectionType":"Album","collectionId":1440776729,
           "artistName":"Tokyo Incidents","collectionName":"Kyouiku",
           "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/kyouiku/100x100bb.jpg",
           "releaseDate":"2004-11-25T08:00:00Z","trackCount":11,"primaryGenreName":"Rock"}]}
        """

        // https://api.discogs.com/database/search?type=artist&q=東京事変&per_page=10
        // — captured 2026-09-05, verbatim.
        const val DISCOGS_ARTIST_SEARCH = """
        {"pagination":{"page":1,"pages":1,"per_page":10,"items":1,"urls":{}},"results":[
          {"id":2257146,"type":"artist","master_id":null,"master_url":null,
           "uri":"/artist/2257146-Tokyo-Jihen","title":"Tokyo Jihen",
           "thumb":"https://i.discogs.com/thumb/150x150.jpeg",
           "cover_image":"https://i.discogs.com/cover/600x538.jpeg",
           "resource_url":"https://api.discogs.com/artists/2257146"}]}
        """

        // https://api.discogs.com/artists/2257146 — captured 2026-09-05, trimmed to the read fields.
        const val DISCOGS_ARTIST_DETAIL = """
        {"name":"Tokyo Jihen","id":2257146,
         "resource_url":"https://api.discogs.com/artists/2257146",
         "uri":"https://www.discogs.com/artist/2257146-Tokyo-Jihen",
         "images":[{"type":"primary","uri":"https://i.discogs.com/cover/600x538.jpeg",
                    "resource_url":"https://i.discogs.com/cover/600x538.jpeg",
                    "uri150":"https://i.discogs.com/thumb/150x150.jpeg","width":600,"height":538}],
         "realname":"東京事変","profile":"Japanese five-piece rock band.",
         "namevariations":["Tokyo Incidents","東京事變"],"members":[],"data_quality":"Needs Vote"}
        """
    }
}
