package com.landofoz.musicmeta.provider

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
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
    fun `a homonym counter on the discogs detail record does not reject the hit the search accepted`() = runTest {
        // Given - a Korean request whose Discogs record is a romanization and a counter, no other names
        httpClient.givenJsonResponse("api.discogs.com/database/search", DISCOGS_IU_ARTIST_SEARCH)
        httpClient.givenJsonResponse("api.discogs.com/artists", DISCOGS_IU_ARTIST_DETAIL_UNCORROBORATED)
        val provider = DiscogsProvider("token", httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("아이유")

        // When - the artist photo is asked for
        val result = withPool(IU_POOL) { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - the detail name reads the same as the search hit did, so the pool verifies it once more
        assertTrue("expected the detail re-check to accept the hit, got $result", result is EnrichmentResult.Success)
        assertEquals(
            DISCOGS_ARTIST_PRIMARY_ALIAS_CONFIDENCE,
            (result as EnrichmentResult.Success).confidence,
            0.0001f,
        )
    }

    @Test
    fun `a discogs record whose own realname is the requested name reports the canonical tier`() = runTest {
        // Given - a request in Japanese, and Discogs filing that very name as the artist's realname
        httpClient.givenJsonResponse("api.discogs.com/database/search", DISCOGS_ARTIST_SEARCH)
        httpClient.givenJsonResponse("api.discogs.com/artists", DISCOGS_ARTIST_DETAIL)
        val provider = DiscogsProvider("token", httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - the artist photo is asked for
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - Discogs itself holds the requested name, so no alias tier scales the score down
        assertTrue("expected a match through the pool, got $result", result is EnrichmentResult.Success)
        assertEquals(
            DISCOGS_ARTIST_CONFIDENCE,
            (result as EnrichmentResult.Success).confidence,
            0.0001f,
        )
    }

    @Test
    fun `a discogs record whose name variations hold the requested name is the requested act`() = runTest {
        // Given - a Korean request whose Discogs record answers under a romanization and a homonym counter
        httpClient.givenJsonResponse("api.discogs.com/database/search", DISCOGS_IU_ARTIST_SEARCH)
        httpClient.givenJsonResponse("api.discogs.com/artists", DISCOGS_IU_ARTIST_DETAIL)
        val provider = DiscogsProvider("token", httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("아이유")

        // When - the artist photo is asked for
        val result = withPool(IU_POOL) { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - the record's own name variations carry the requested name, at the canonical tier
        assertTrue("expected the record's own names to verify it, got $result", result is EnrichmentResult.Success)
        assertEquals(
            DISCOGS_ARTIST_CONFIDENCE,
            (result as EnrichmentResult.Success).confidence,
            0.0001f,
        )
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
    fun `the alias source is never resolved when the requested name matches`() = runTest {
        // Given - a Latin request Deezer answers under the very name it asked with
        httpClient.givenJsonResponse("search/track", DEEZER_TRACK_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")
        var calls = 0
        val names = ResolvedEntityNames()
        names.offerAliases {
            calls++
            TOKYO_JIHEN_POOL
        }

        // When - the track resolves on the requested name alone
        val result = withContext(names) { provider.enrich(request, EnrichmentType.TRACK_METADATA) }

        // Then - the source that would have cost a MusicBrainz lookup was never asked
        assertTrue("expected a match, got $result", result is EnrichmentResult.Success)
        assertEquals(EXACT_CONFIDENCE, (result as EnrichmentResult.Success).confidence, 0.0001f)
        assertEquals(0, calls)
    }

    @Test
    fun `a candidate verified by a search-hint alias reports the lower alias tier`() = runTest {
        // Given - a pool whose only match for the returned candidate is a non-official alias
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_SEARCH_HINT_ARTIST_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - the pool is consulted for it
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - accepted, but at the weaker of the two alias tiers
        assertTrue("expected a match through the pool, got $result", result is EnrichmentResult.Success)
        assertEquals(ALIAS_CONFIDENCE, (result as EnrichmentResult.Success).confidence, 0.0001f)
    }

    @Test
    fun `a candidate under the requested name beats one under an alias`() = runTest {
        // Given - one hit named as the request asked and one matching a non-official alias only
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_CANONICAL_AND_ALIAS_SEARCH)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forArtist("東京事変")

        // When - both are in one result page
        val result = withPool { provider.enrich(request, EnrichmentType.ARTIST_PHOTO) }

        // Then - the requested name wins outright, at the unscaled confidence
        assertTrue("expected a match, got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(EXACT_CONFIDENCE, success.confidence, 0.0001f)
        assertEquals("2", success.resolvedIdentifiers?.get(IdentifierNamespace.DEEZER))
    }

    @Test
    fun `an album under an official alias beats one under a search hint listed first`() = runTest {
        // Given - two albums, the first credited under a search hint and the second under a name
        // the entity is published under
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_ALBUM_SEARCH_TWO_ALIASES)
        val provider = DeezerProvider(httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forAlbum("Kyouiku", "東京事変")

        // When - the album is selected through the pool
        val result = withPool { provider.enrich(request, EnrichmentType.ALBUM_ART) }

        // Then - the published name wins on tier, not on the order Deezer returned them in
        assertTrue("expected a match, got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(PRIMARY_ALIAS_CONFIDENCE, success.confidence, 0.0001f)
        val artwork = success.data as EnrichmentData.Artwork
        assertTrue("picked the search-hint credit: ${artwork.url}", artwork.url.contains("official"))
    }

    @Test
    fun `a discogs release under an official alias beats a search-hint credit listed first`() = runTest {
        // Given - two releases whose combined titles credit two different aliases of one entity
        httpClient.givenJsonResponse("api.discogs.com/database/search", DISCOGS_RELEASE_SEARCH_TWO_ALIASES)
        val provider = DiscogsProvider("token", httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forAlbum("Kyouiku", "東京事変")

        // When - the release is selected through the pool
        val result = withPool { provider.enrich(request, EnrichmentType.ALBUM_ART) }

        // Then - the published name wins on tier, not on Discogs's own result order
        assertTrue("expected a match, got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals(PRIMARY_ALIAS_CONFIDENCE, success.confidence, 0.0001f)
        val artwork = success.data as EnrichmentData.Artwork
        assertTrue("picked the search-hint credit: ${artwork.url}", artwork.url.contains("official"))
    }

    @Test
    fun `a discogs release credited to two artists answers the one the request names`() = runTest {
        // Given - a Greek request, and Discogs crediting the release to her and a second artist
        httpClient.givenJsonResponse("api.discogs.com/database/search", DISCOGS_JOINT_CREDIT_RELEASE_SEARCH)
        val provider = DiscogsProvider("token", httpClient, RateLimiter(0))
        val request = EnrichmentRequest.forAlbum("Ξημερώνει", "Χάρις Αλεξίου")

        // When - the album art is asked for
        val result = withPool(ALEXIOU_POOL) { provider.enrich(request, EnrichmentType.ALBUM_ART) }

        // Then - the credit is read as the two artists it names, and her own name identifies it
        assertTrue("expected the credited artist to be found, got $result", result is EnrichmentResult.Success)
        assertEquals(EXACT_CONFIDENCE, (result as EnrichmentResult.Success).confidence, 0.0001f)
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

    private suspend fun <T> withPool(pool: List<AlternativeName> = TOKYO_JIHEN_POOL, body: suspend () -> T): T {
        val names = ResolvedEntityNames()
        names.offerAliases { pool }
        return withContext(names) { body() }
    }

    private companion object {
        /** `fuzzyMatch(hasArtistMatch = true)`, the value every artist-search path reports today. */
        const val EXACT_CONFIDENCE = 0.8f

        /** [EXACT_CONFIDENCE] scaled by `NameMatchTier.PRIMARY_ALIAS`. */
        const val PRIMARY_ALIAS_CONFIDENCE = 0.8f * 0.95f

        /** [EXACT_CONFIDENCE] scaled by `NameMatchTier.ALIAS` — a search hint, not a published name. */
        const val ALIAS_CONFIDENCE = 0.8f * 0.85f

        /**
         * `fuzzyMatch(hasArtistMatch = false)` scaled by `NameMatchTier.PRIMARY_ALIAS`: the Discogs
         * artist endpoints match on one name only, so a pool-verified hit there reports this.
         */
        const val DISCOGS_ARTIST_PRIMARY_ALIAS_CONFIDENCE = 0.6f * 0.95f

        /**
         * `fuzzyMatch(hasArtistMatch = false)`, unscaled: the Discogs artist endpoints match on one
         * name only, so this is what a canonical-tier hit there reports.
         */
        const val DISCOGS_ARTIST_CONFIDENCE = 0.6f

        // The pool as MusicBrainz files it: a locale-tagged or primary alias is a name the entity
        // is published under, a "Search hint" is not.
        // Source order puts a search hint ahead of a published name on purpose: MusicBrainz sorts
        // its aliases array alphabetically, so the array is no guide to which name to prefer.
        val TOKYO_JIHEN_POOL = listOf(
            AlternativeName("東京事変", official = true),
            AlternativeName("Toukyou Jihen", official = false),
            AlternativeName("東京事變", official = false),
            AlternativeName("Tokyo Jihen", official = true),
            AlternativeName("Tokyo Incidents", official = true),
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

        // Same endpoint, answering only under a name the pool holds as a search hint.
        const val DEEZER_SEARCH_HINT_ARTIST_SEARCH = """
        {"data":[
          {"id":90002,"name":"Toukyou Jihen","link":"https://www.deezer.com/artist/90002",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/tj/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/tj/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/tj/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/tj/1000x1000.jpg",
           "nb_album":3,"nb_fan":40,"radio":true,"type":"artist"}
        ],"total":1}
        """

        // Same endpoint, with a search-hint match listed ahead of the requested name itself.
        const val DEEZER_CANONICAL_AND_ALIAS_SEARCH = """
        {"data":[
          {"id":90002,"name":"Toukyou Jihen","link":"https://www.deezer.com/artist/90002",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/tj/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/tj/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/tj/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/tj/1000x1000.jpg",
           "nb_album":3,"nb_fan":40,"radio":true,"type":"artist"},
          {"id":2,"name":"東京事変","link":"https://www.deezer.com/artist/2",
           "picture_small":"https://cdn-images.dzcdn.net/images/artist/tk/56x56.jpg",
           "picture_medium":"https://cdn-images.dzcdn.net/images/artist/tk/250x250.jpg",
           "picture_big":"https://cdn-images.dzcdn.net/images/artist/tk/500x500.jpg",
           "picture_xl":"https://cdn-images.dzcdn.net/images/artist/tk/1000x1000.jpg",
           "nb_album":45,"nb_fan":2487,"radio":true,"type":"artist"}
        ],"total":2}
        """

        // https://api.deezer.com/search/album?q=… — the search-hint credit listed first.
        const val DEEZER_ALBUM_SEARCH_TWO_ALIASES = """
        {"data":[
          {"id":501,"title":"Kyouiku","cover_small":"https://cdn-images.dzcdn.net/hint/56.jpg",
           "cover_medium":"https://cdn-images.dzcdn.net/hint/250.jpg",
           "cover_big":"https://cdn-images.dzcdn.net/hint/500.jpg",
           "cover_xl":"https://cdn-images.dzcdn.net/hint/1000.jpg",
           "nb_tracks":11,"artist":{"id":90002,"name":"Toukyou Jihen"},"type":"album"},
          {"id":502,"title":"Kyouiku","cover_small":"https://cdn-images.dzcdn.net/official/56.jpg",
           "cover_medium":"https://cdn-images.dzcdn.net/official/250.jpg",
           "cover_big":"https://cdn-images.dzcdn.net/official/500.jpg",
           "cover_xl":"https://cdn-images.dzcdn.net/official/1000.jpg",
           "nb_tracks":11,"artist":{"id":384298,"name":"Tokyo Incidents"},"type":"album"}
        ],"total":2}
        """

        // https://api.deezer.com/search/track?q=artist:"Radiohead" track:"Karma Police"
        // — the shape a direct match arrives in.
        const val DEEZER_TRACK_SEARCH = """
        {"data":[
          {"id":789,"title":"Karma Police","artist":{"id":399,"name":"Radiohead"},
           "album":{"title":"OK Computer"},"duration":263}
        ]}
        """

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

        // https://api.discogs.com/database/search?type=release&q=… — the search-hint credit first.
        const val DISCOGS_RELEASE_SEARCH_TWO_ALIASES = """
        {"results":[
          {"title":"Toukyou Jihen - Kyouiku","label":["EMI"],"year":"2004","country":"Japan",
           "cover_image":"https://img.discogs.com/hint.jpg","id":601},
          {"title":"Tokyo Incidents - Kyouiku","label":["EMI"],"year":"2004","country":"Japan",
           "cover_image":"https://img.discogs.com/official.jpg","id":602}
        ]}
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

        // The pool as MusicBrainz files it for artist 2dc67a2d-5914-4064-bf7b-8c8ea77bfab6,
        // captured 2026-09-05, less two mojibake aliases: nothing in it is the joint credit
        // Discogs publishes, so the pool cannot verify that credit whole.
        val ALEXIOU_POOL = listOf(
            AlternativeName("Χάρις Αλεξίου", official = true),
            AlternativeName("Charis Alexiou", official = false),
            AlternativeName("Charoula Alexiou", official = false),
            AlternativeName("Haris Alexiou", official = true),
            AlternativeName("Kharis Alexiou", official = false),
            AlternativeName("Xaris Alexiou", official = false),
            AlternativeName("Xaroula Aleksiou", official = false),
            AlternativeName("ΧΑΡΙΣ ΑΛΕΞΙΟΥ", official = false),
            AlternativeName("Χαρούλα Αλεξίου", official = false),
        )

        // https://api.discogs.com/database/search?type=release&title=Ξημερώνει&artist=Χάρις Αλεξίου&per_page=5
        // — captured 2026-09-05, first hit only, trimmed to the read fields, image URLs shortened.
        const val DISCOGS_JOINT_CREDIT_RELEASE_SEARCH = """
        {"results":[
          {"id":9326133,"type":"release","master_id":583829,
           "title":"Χάρις Αλεξίου, Αντώνης Βαρδής - Ξημερώνει",
           "year":"1994","country":"Greece","catno":"7243 4 80188 2 3","format":["CD","Album"],
           "label":["Minos","Minos Matsas & Son Co. Ltd."],
           "thumb":"https://i.discogs.com/thumb/150x150.jpeg",
           "cover_image":"https://i.discogs.com/cover/600x519.jpeg",
           "resource_url":"https://api.discogs.com/releases/9326133"}
        ]}
        """

        // The pool as MusicBrainz files it for artist b9545342-1e6d-4dae-84ac-013374ad8d7c,
        // captured 2026-09-05: every alias is primary or locale-tagged, so all are official here.
        val IU_POOL = listOf(
            AlternativeName("IU", official = true),
            AlternativeName("Lee Ji-eun", official = true),
            AlternativeName("아이유", official = true),
            AlternativeName("이지은", official = true),
        )

        // https://api.discogs.com/database/search?type=artist&q=아이유&per_page=10
        // — captured 2026-09-05, trimmed to the read fields, image URLs shortened.
        const val DISCOGS_IU_ARTIST_SEARCH = """
        {"pagination":{"page":1,"pages":1,"per_page":10,"items":1,"urls":{}},"results":[
          {"id":2226306,"type":"artist","master_id":null,"master_url":null,
           "uri":"/artist/2226306-IU-3","title":"IU (3)",
           "thumb":"https://i.discogs.com/thumb/150x150.jpeg",
           "cover_image":"https://i.discogs.com/cover/600x600.jpeg",
           "resource_url":"https://api.discogs.com/artists/2226306"}]}
        """

        // https://api.discogs.com/artists/2226306
        // — captured 2026-09-05, trimmed to the read fields, image URLs shortened.
        const val DISCOGS_IU_ARTIST_DETAIL = """
        {"name":"IU (3)","id":2226306,
         "resource_url":"https://api.discogs.com/artists/2226306",
         "uri":"https://www.discogs.com/artist/2226306-IU-3",
         "images":[{"type":"primary","uri":"https://i.discogs.com/cover/600x600.jpeg",
                    "resource_url":"https://i.discogs.com/cover/600x600.jpeg",
                    "uri150":"https://i.discogs.com/thumb/150x150.jpeg","width":600,"height":600}],
         "realname":"이지은","namevariations":["아이유","아이유 IU"],"data_quality":"Needs Vote"}
        """

        // [DISCOGS_IU_ARTIST_DETAIL] less its `realname` and `namevariations`, which hold the
        // requested name and so would identify this record by corroboration alone. Only the
        // disambiguator strip can accept the hit here, which is what the homonym-counter test pins;
        // reading the full capture there would leave that test green with the strip reverted.
        const val DISCOGS_IU_ARTIST_DETAIL_UNCORROBORATED = """
        {"name":"IU (3)","id":2226306,
         "resource_url":"https://api.discogs.com/artists/2226306",
         "uri":"https://www.discogs.com/artist/2226306-IU-3",
         "images":[{"type":"primary","uri":"https://i.discogs.com/cover/600x600.jpeg",
                    "resource_url":"https://i.discogs.com/cover/600x600.jpeg",
                    "uri150":"https://i.discogs.com/thumb/150x150.jpeg","width":600,"height":600}],
         "data_quality":"Needs Vote"}
        """
    }
}
