package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzEnricher
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A provider's own memo lives for one [EnrichmentEngine.enrich] call and no longer.
 *
 * Two properties, and both have to hold. MusicBrainz answers several enrichment types from one
 * upstream resource and only the provider knows it — [EnrichmentCache] is keyed by type, so GENRE's
 * answer never serves ALBUM_TRACKS's — which is why the memo exists at all. And nothing it holds
 * outlives the call, which is what keeps `forceRefresh`, `invalidate()` and `cache.clear()` honest.
 */
class ProviderMemoLifetimeTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    // mergers excluded so GENRE has one predictable source, which is what makes the request counts
    // below readable: it is IDENTITY_TYPES-eligible, so identity resolution answers it directly,
    // where stock mergers would route it through GenreMerger to the provider chain instead.
    private fun engine(identityResolution: Boolean = true) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = identityResolution),
        mergers = emptyList(),
    )

    private fun releaseLookups(mbid: String) = httpClient.requestedUrls.count { it.contains("release/$mbid?") }

    private fun genresOf(results: com.landofoz.musicmeta.EnrichmentResults): List<String> {
        val result = results.raw[EnrichmentType.GENRE]
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return ((result as EnrichmentResult.Success).data as EnrichmentData.Metadata).genres.orEmpty()
    }

    @Test
    fun `forceRefresh refetches the release instead of serving the memo the first call filled`() = runTest {
        // Given - an album whose GENRE comes from the release lookup, already enriched once
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("alternative rock"))
        val engine = engine()
        engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))

        // When - MusicBrainz corrects the release and the consumer asks for fresh data
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("art rock"))
        val refreshed = engine.enrich(ALBUM, setOf(EnrichmentType.GENRE), forceRefresh = true)

        // Then - the correction is what the consumer gets, from a second upstream lookup
        assertEquals(listOf("art rock"), genresOf(refreshed))
        assertEquals(2, releaseLookups("thin1"))
    }

    @Test
    fun `an invalidated type refetches instead of being answered from the provider's memo`() = runTest {
        // Given - the same album, enriched once and then invalidated
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("alternative rock"))
        val engine = engine()
        engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))
        engine.invalidate(ALBUM, EnrichmentType.GENRE)

        // When - MusicBrainz corrects the release and the consumer enriches again
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("art rock"))
        val second = engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))

        // Then - invalidate reached the provider's memo too, so the correction lands
        assertEquals(listOf("art rock"), genresOf(second))
    }

    @Test
    fun `a cache cleared directly by the consumer is not undone by the provider's memo`() = runTest {
        // Given - the same album, enriched once and then cleared through the engine's cache property
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("alternative rock"))
        val engine = engine()
        engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))
        engine.cache.clear()

        // When - MusicBrainz corrects the release and the consumer enriches again
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("art rock"))
        val second = engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))

        // Then - the correction lands, on a path no engine-side hook could have covered: `cache` is
        // a public property, so this clear never enters the engine at all
        assertEquals(listOf("art rock"), genresOf(second))
    }

    @Test
    fun `one album's fanout looks the release up once, not once per type`() = runTest {
        // Given - a search hit thin enough that GENRE needs the full release, and ALBUM_TRACKS,
        // which needs that same release for its tracklist
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("alternative rock"))

        // When - both types are enriched in one call
        val results = engine().enrich(ALBUM, setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_TRACKS))

        // Then - both resolved off a single lookup, not one each on a 1 req/s limiter
        assertEquals(listOf("alternative rock"), genresOf(results))
        assertTrue(
            "expected a tracklist, got ${results.raw[EnrichmentType.ALBUM_TRACKS]}",
            results.raw[EnrichmentType.ALBUM_TRACKS] is EnrichmentResult.Success,
        )
        assertEquals(1, releaseLookups("thin1"))
    }

    @Test
    fun `one album's fanout looks the release-group's wiki links up once, not once per type`() = runTest {
        // Given - a search hit rich enough to answer both types itself, so each type's only extra
        // call is the release-group wiki lookup a release search never embeds
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_SEARCH_HIGH_SCORE)
        httpClient.givenJsonResponse(RELEASE_GROUP, RELEASE_GROUP_WITH_WIKI_RELATIONS)

        // When - two types are resolved in one call, each through the provider (identity resolution
        // off, so LABEL is not simply handed identity's own payload)
        val results = engine(identityResolution = false)
            .enrich(ALBUM, setOf(EnrichmentType.GENRE, EnrichmentType.LABEL))

        // Then - both resolved, and the wiki lookup was made once between them
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.Success)
        assertEquals(1, httpClient.requestedUrls.count { it.contains(RELEASE_GROUP) })
    }

    @Test
    fun `one album's fanout searches for the album once, not once per type`() = runTest {
        // Given - an album carrying no MBID, so every type has to resolve it by title and artist
        httpClient.givenJsonResponse(RELEASE_SEARCH, RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse(RELEASE_LOOKUP, releaseLookup("alternative rock"))

        // When - two types are enriched in one call, identity resolution off so that the MBID it
        // would merge into the request does not spare the second type the search
        val results = engine(identityResolution = false)
            .enrich(ALBUM, setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_TRACKS))

        // Then - both resolved off one search, not one each on a 1 req/s limiter
        assertEquals(listOf("alternative rock"), genresOf(results))
        assertTrue(
            "expected a tracklist, got ${results.raw[EnrichmentType.ALBUM_TRACKS]}",
            results.raw[EnrichmentType.ALBUM_TRACKS] is EnrichmentResult.Success,
        )
        assertEquals(1, httpClient.requestedUrls.count { it.contains(RELEASE_SEARCH) })
    }

    @Test
    fun `an absent album's fanout spends the symbol fallback once, not once per type`() = runTest {
        // Given - a title no ASCII spelling finds, whose artist resolves exactly but whose catalogue
        // holds no folded match, so the fallback reads every browse page it is allowed
        httpClient.givenJsonResponse(RELEASE_SEARCH, NO_RELEASES)
        httpClient.givenJsonResponse(ARTIST_SEARCH, ARTIST_SEARCH_EXACT)
        httpClient.givenJsonResponse(BROWSE, FULL_BROWSE_PAGE)

        // When - three album types are enriched in one call
        val results = engine().enrich(
            SYMBOL_ALBUM,
            setOf(EnrichmentType.GENRE, EnrichmentType.LABEL, EnrichmentType.ALBUM_TRACKS),
        )

        // Then - no type resolved, and the miss cost one run of the ladder, one artist search and
        // one run of browse pages for the whole call rather than one of each per type. The release
        // searches are counted because they are what a per-type repeat shows up as first.
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.LABEL] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.ALBUM_TRACKS] is EnrichmentResult.NotFound)
        assertEquals(RELEASE_SEARCHES_PER_ABSENT_ALBUM, httpClient.requestedUrls.count { it.contains(RELEASE_SEARCH) })
        assertEquals(1, httpClient.requestedUrls.count { it.contains(ARTIST_SEARCH) })
        assertEquals(
            MusicBrainzEnricher.SYMBOL_FALLBACK_MAX_PAGES,
            httpClient.requestedUrls.count { it.contains(BROWSE) },
        )
    }

    @Test
    fun `forceRefresh re-resolves which album a title means, not only that album's payload`() = runTest {
        // Given - a title MusicBrainz first answers with the wrong release, enriched once
        httpClient.givenJsonResponse(RELEASE_SEARCH, searchHit("wrong1"))
        val engine = engine(identityResolution = false)
        engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))

        // When - MusicBrainz corrects the mis-titled release and the consumer forces a refresh
        httpClient.givenJsonResponse(RELEASE_SEARCH, searchHit("right1"))
        val refreshed = engine.enrich(ALBUM, setOf(EnrichmentType.GENRE), forceRefresh = true)

        // Then - the refresh resolved the title again rather than reusing the album it first picked.
        // This is the one memo that holds an identity, so it is the one whose lifetime can serve a
        // wrong entity that no refresh could ever correct.
        val success = refreshed.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals("right1", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `two providers sharing an id each answer with their own configuration`() = runTest {
        // Given - two MusicBrainz providers alike but for the score floor each accepts, and a hit
        // only the lower floor will resolve. Identity resolution is off because the strict provider
        // is also the identity provider, and the suggestions on its NotFound would short-circuit
        // the fan-out before the lenient one ran.
        httpClient.givenJsonResponse(RELEASE_SEARCH, searchHit("thin1", score = 80))
        val strict = MusicBrainzProvider(httpClient, RateLimiter(0), minMatchScore = 95)
        val lenient = MusicBrainzProvider(httpClient, RateLimiter(0), minMatchScore = 50)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(strict, lenient)),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
            mergers = emptyList(),
        )

        // When - GENRE is enriched, so the strict provider declines and the chain falls through
        val results = engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))

        // Then - the lenient provider answered on its own floor. Both carry the id "musicbrainz",
        // so a slot keyed on that would have handed it the enricher the strict one built, and the
        // floor that enricher was constructed with.
        assertEquals(listOf("alternative rock"), genresOf(results))
    }

    @Test
    fun `one artist's fanout looks the artist up once, not once per type`() = runTest {
        // Given - an artist known by MBID, whose members and links come from one lookup
        httpClient.givenJsonResponse("artist/art1?", ARTIST_LOOKUP_MEMBERS_AND_LINKS)
        val artist = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When - both artist types are enriched in one call
        val results = engine().enrich(artist, setOf(EnrichmentType.BAND_MEMBERS, EnrichmentType.ARTIST_LINKS))

        // Then - one lookup answered both
        assertTrue(results.raw[EnrichmentType.BAND_MEMBERS] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.ARTIST_LINKS] is EnrichmentResult.Success)
        assertEquals(1, httpClient.requestedUrls.count { it.contains("artist/art1?") })
    }

    private companion object {
        val ALBUM = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        /** A title MusicBrainz stores only under symbols (`F♯ A♯ ∞`), so no spelling searches for it. */
        val SYMBOL_ALBUM = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        const val RELEASE_SEARCH = "release?query"
        const val RELEASE_LOOKUP = "release/thin1"
        const val RELEASE_GROUP = "release-group/group123"
        const val ARTIST_SEARCH = "artist?query"
        const val BROWSE = "release-group?artist="

        /**
         * `release?query=` requests one absent album costs: the ladder's strict search, then the
         * fuzzy search that answers the empty pool with suggestions. [SYMBOL_ALBUM]'s "(Infinity)"
         * is not a qualifier group, so the qualifier fallback searches nothing between them.
         *
         * Both are spent once for the call. A per-type repeat of either is what this number catches,
         * and the fuzzy one is only reached when nothing strict resolved.
         */
        const val RELEASE_SEARCHES_PER_ABSENT_ALBUM = 2

        /** What MusicBrainz really answers a search for a symbol title's ASCII spelling with. */
        const val NO_RELEASES = """{"count": 0, "offset": 0, "releases": []}"""

        /** Exact enough for the fallback to browse this artist's catalogue rather than stop. */
        val ARTIST_SEARCH_EXACT = """
            {
              "artists": [{
                "id": "gybe1",
                "score": 100,
                "name": "Godspeed You! Black Emperor"
              }]
            }
        """.trimIndent()

        /** A full page of non-matching groups, so the browse pages on instead of ending early. */
        val FULL_BROWSE_PAGE = (1..MusicBrainzApi.BROWSE_PAGE_SIZE).joinToString(
            separator = ",",
            prefix = """{"release-groups": [""",
            postfix = "]}",
        ) { """{"id": "rg$it", "title": "Live at Somewhere $it", "primary-type": "Album"}""" }

        /** A resolved hit for [ALBUM]'s title carrying its own tags, so GENRE needs no release lookup. */
        fun searchHit(releaseId: String, score: Int = 98) = """
            {
              "releases": [{
                "id": "$releaseId",
                "score": $score,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "art1", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {
                  "id": "group-$releaseId",
                  "primary-type": "Album",
                  "tags": [{"name": "alternative rock", "count": 5}]
                }
              }]
            }
        """.trimIndent()

        /** A search hit with no tags, so GENRE has to fetch the release itself. */
        val RELEASE_SEARCH_THIN = """
            {
              "releases": [{
                "id": "thin1",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "art1", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "release-group": {"id": "group123", "primary-type": "Album"}
              }]
            }
        """.trimIndent()

        fun releaseLookup(genre: String) = """
            {
              "id": "thin1",
              "title": "OK Computer",
              "date": "1997-06-16",
              "country": "GB",
              "label-info": [{"label": {"name": "Parlophone"}}],
              "media": [{"tracks": [{"title": "Airbag", "position": 1}]}],
              "release-group": {
                "id": "group123",
                "primary-type": "Album",
                "tags": [{"name": "$genre", "count": 5}]
              }
            }
        """.trimIndent()

        /** A search hit carrying tags and a label, so GENRE and LABEL both resolve without a lookup. */
        val RELEASE_SEARCH_HIGH_SCORE = """
            {
              "releases": [{
                "id": "abc123",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "art1", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {
                  "id": "group123",
                  "primary-type": "Album",
                  "tags": [{"name": "alternative rock", "count": 5}]
                }
              }]
            }
        """.trimIndent()

        val RELEASE_GROUP_WITH_WIKI_RELATIONS = """
            {
              "id": "group123",
              "relations": [
                {
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q82539"}
                },
                {
                  "type": "wikipedia",
                  "url": {"resource": "https://en.wikipedia.org/wiki/OK_Computer"}
                }
              ]
            }
        """.trimIndent()

        val ARTIST_LOOKUP_MEMBERS_AND_LINKS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "alternative rock", "count": 5}],
              "relations": [
                {
                  "type": "member of band",
                  "direction": "backward",
                  "artist": {"id": "m1", "name": "Thom Yorke"},
                  "attributes": ["lead vocals"],
                  "begin": "1985",
                  "ended": false
                },
                {
                  "type": "official homepage",
                  "target-type": "url",
                  "url": {"resource": "https://radiohead.com"}
                }
              ]
            }
        """.trimIndent()
    }
}
