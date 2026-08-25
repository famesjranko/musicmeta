package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
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
import org.junit.Assert.assertThrows
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
    fun `slot rejects a String owner rather than silently sharing it with an unrelated caller`() {
        // Given - a fresh call scope, and a String passed as the owner instead of a component
        val scope = ProviderCallScope()

        // When - a caller slots state under that String
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            scope.slot("cache") { Any() }
        }

        // Then - refused rather than silently shared with whichever other caller uses the same
        // interned literal
        assertTrue(thrown.message!!.contains("slot owner must be the component holding the state"))
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

    /**
     * Two MusicBrainz providers configured differently, through a real engine. Both build their
     * enricher from the id `"musicbrainz"` — only the registered wrapper's id differs — so a slot
     * keyed on `provider.id` rather than the instance would hand the second the enricher the first
     * built, and with it the score floor that enricher was constructed with.
     */
    @Test
    fun `two configurations of one provider each answer on their own floor`() = runTest {
        // Given - a hit only the lower score floor accepts, behind two differently-configured
        // MusicBrainz providers. Identity resolution is off because the strict one is also the
        // identity provider, and the suggestions on its NotFound would short-circuit the fan-out.
        httpClient.givenJsonResponse(RELEASE_SEARCH, searchHit("thin1", score = 80))
        val strict = MusicBrainzProvider(httpClient, RateLimiter(0), minMatchScore = 95)
        val lenient = MusicBrainzProvider(httpClient, RateLimiter(0), minMatchScore = 50)
        val engine = DefaultEnrichmentEngine(
            ProviderRegistry(
                listOf(
                    RenamedProvider(strict, "musicbrainz_strict"),
                    RenamedProvider(lenient, "musicbrainz_lenient"),
                ),
            ),
            FakeEnrichmentCache(),
            EnrichmentConfig(enableIdentityResolution = false),
            mergers = emptyList(),
        )

        // When - GENRE is enriched, so the strict provider declines and the chain falls through
        val results = engine.enrich(ALBUM, setOf(EnrichmentType.GENRE))

        // Then - the lenient provider answered on its own floor, not the strict one's
        assertEquals(listOf("alternative rock"), genresOf(results))
    }

    /**
     * One provider under another id, so two instances of a fixed-id provider can both register.
     * Everything but [id] is the delegate's, including the `this` its enricher memo keys on.
     */
    private class RenamedProvider(
        delegate: EnrichmentProvider,
        override val id: String,
    ) : EnrichmentProvider by delegate

    /**
     * The same invariant at the seam itself. [EnrichmentProvider] states no `equals`/`hashCode`
     * contract, so a consumer's provider written as a `data class` hands two differently-configured
     * instances one key without either of them ever repeating an id.
     */
    @Test
    fun `two provider instances alike in every field still get their own slot`() {
        // Given - a call scope and two instances a data-class equals could not tell apart
        val scope = ProviderCallScope()
        val strict = MusicBrainzProvider(httpClient, RateLimiter(0), minMatchScore = 95)
        val lenient = MusicBrainzProvider(httpClient, RateLimiter(0), minMatchScore = 50)

        // When - each asks the scope for the state it memoizes for this call
        val strictSlot = scope.slot(strict) { "strict" }
        val lenientSlot = scope.slot(lenient) { "lenient" }

        // Then - neither was handed whichever filled its slot first
        assertEquals("strict", strictSlot)
        assertEquals("lenient", lenientSlot)
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

    @Test
    fun `one track's fanout searches for the recording once, not once per type`() = runTest {
        // Given - a track carrying no MBID, whose best recording has no tags of its own, so GENRE
        // is not answered by the identity payload and reaches the provider chain like any other type
        httpClient.givenJsonResponse(RECORDING_SEARCH, RECORDING_SEARCH_HIT)

        // When - two track types are enriched in one call
        val results = engine().enrich(TRACK, setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA))

        // Then - one search served the call, not identity's own plus one per type. Every one of
        // those repeats is a fresh ranking of a pool MusicBrainz is free to reorder, so the saving
        // is a consistent answer as much as a request on the 1 req/s limiter.
        assertTrue(
            "expected track metadata, got ${results.raw[EnrichmentType.TRACK_METADATA]}",
            results.raw[EnrichmentType.TRACK_METADATA] is EnrichmentResult.Success,
        )
        assertEquals(1, httpClient.requestedUrls.count { it.contains(RECORDING_SEARCH) })
    }

    @Test
    fun `an absent track's fanout spends the search ladder once, not once per type`() = runTest {
        // Given - a track MusicBrainz holds no recording of at all, so nothing on the ladder resolves
        httpClient.givenJsonResponse(RECORDING_SEARCH, NO_RECORDINGS)

        // When - two track types are enriched in one call. Nothing suggests either, so identity does
        // not short-circuit the fan-out and both types run the whole ladder for themselves.
        val results = engine().enrich(TRACK, setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA))

        // Then - no type resolved, and the miss cost one run of the ladder for the call
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.TRACK_METADATA] is EnrichmentResult.NotFound)
        assertEquals(
            RECORDING_SEARCHES_PER_ABSENT_TRACK,
            httpClient.requestedUrls.count { it.contains(RECORDING_SEARCH) },
        )

        // And - per-URL, not only the total: the canonical (`-comment:*` filtered) search and the
        // plain search it falls back to are genuinely different queries and must stay that way. A
        // total alone is satisfied by a wrong fix that collapses these two into the shared plain
        // pool as readily as by the correct one, which only collapses the plain search's own two
        // callers. Quoted-title only, so the third, unquoted fuzzy near-miss search (also part of
        // the total above) is excluded from both buckets rather than inflating the plain one.
        val recordingSearches = httpClient.requestedUrls.filter { it.contains(RECORDING_SEARCH) }
        val quotedTitleSearches = recordingSearches.filter { it.contains(QUOTED_TITLE_TERM) }
        val canonicalSearches = quotedTitleSearches.filter { it.contains(CANONICAL_FILTER_TERM) }
        val plainSearches = quotedTitleSearches.filter { !it.contains(CANONICAL_FILTER_TERM) }
        assertEquals("the canonical, `-comment:*`-filtered search", 1, canonicalSearches.size)
        assertEquals("the plain, unfiltered search", 1, plainSearches.size)
    }

    @Test
    fun `an absent artist's fanout searches by name once, not once per type`() = runTest {
        // Given - a name MusicBrainz holds no artist under, whose fuzzy retry is also empty, so
        // nothing suggests and identity resolution does not short-circuit the fan-out
        httpClient.givenJsonResponse(ARTIST_SEARCH, NO_ARTISTS)

        // When - four artist-only types are enriched in one call, none of them carrying an MBID
        val results = engine().enrich(
            ABSENT_ARTIST,
            setOf(
                EnrichmentType.GENRE, EnrichmentType.BAND_MEMBERS,
                EnrichmentType.ARTIST_DISCOGRAPHY, EnrichmentType.ARTIST_LINKS,
            ),
        )

        // Then - no type resolved, and the miss cost one strict search and one fuzzy retry for the
        // whole call rather than one of each per type
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.BAND_MEMBERS] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.ARTIST_DISCOGRAPHY] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.ARTIST_LINKS] is EnrichmentResult.NotFound)
        assertEquals(ARTIST_SEARCHES_PER_ABSENT_ARTIST, httpClient.requestedUrls.count { it.contains(ARTIST_SEARCH) })
    }

    @Test
    fun `a qualified track title that resolves nothing pays for the whole ladder once, not once per type`() = runTest {
        // Given - a title carrying a "(Remastered)" qualifier group MusicBrainz holds no matching
        // recording under, at any candidate the qualifier fallback tries, and whose fuzzy retry is
        // also empty
        httpClient.givenJsonResponse(RECORDING_SEARCH, NO_RECORDINGS)

        // When - two track types are enriched in one call
        val results = engine().enrich(QUALIFIED_TRACK, setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA))

        // Then - no type resolved, and the miss cost one run of the whole resolution ladder —
        // including the qualifier fallback's own searches — for the call, not once per type. A memo
        // over the raw search alone would leave the fallback repeating and this count higher.
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.NotFound)
        assertTrue(results.raw[EnrichmentType.TRACK_METADATA] is EnrichmentResult.NotFound)
        assertEquals(
            RECORDING_SEARCHES_PER_ABSENT_QUALIFIED_TRACK,
            httpClient.requestedUrls.count { it.contains(RECORDING_SEARCH) },
        )
    }

    @Test
    fun `a qualified track's original title and its stripped fallback candidate stay two distinct searches`() = runTest {
        // Given - the same absent, qualified-title track as above, so both the plain pool memo
        // (shared between the original title's own two callers) and the qualifier fallback's
        // stripped-title candidate run in the same call
        httpClient.givenJsonResponse(RECORDING_SEARCH, NO_RECORDINGS)

        // When - the track is enriched, running the whole absent-qualified-track ladder once
        engine().enrich(QUALIFIED_TRACK, setOf(EnrichmentType.GENRE, EnrichmentType.TRACK_METADATA))

        // Then - a title genuinely changed by the fallback is a genuinely different search: the
        // memo keyed on the original title must not also answer for the stripped candidate, or the
        // fallback's own search never reaches MusicBrainz. Per-URL, not a total — a total is
        // satisfied by a fix that collapses these into one as much as by the correct fix.
        val recordingSearches = httpClient.requestedUrls.filter { it.contains(RECORDING_SEARCH) }
        val originalTitleSearches = recordingSearches.filter {
            it.contains("Paranoid+Android+%5C%28Remastered%5C%29%22")
        }
        val strippedTitleSearches = recordingSearches.filter { it.contains("recording%3A%22Paranoid+Android%22") }
        // The qualified title costs two: its canonical (filtered) and shallow (unfiltered) arms,
        // which differ by the filter term, not the title
        assertEquals("the original qualified title", 2, originalTitleSearches.size)
        assertEquals("the qualifier fallback's stripped candidate", 1, strippedTitleSearches.size)
    }

    @Test
    fun `forceRefresh refetches the plain recording pool instead of serving the memo the first call filled`() = runTest {
        // Given - a hint-less track whose canonical (filtered) pool is always empty, so resolution
        // always falls to the plain pool MusicBrainzApi shares between searchCanonicalRecordings's
        // shallow fallback and the enricher's own suggestion search — already enriched once, when
        // MusicBrainz held nothing under either query
        httpClient.givenJsonResponse("limit=${MusicBrainzApi.CANONICAL_SEARCH_LIMIT}", NO_RECORDINGS)
        httpClient.givenJsonResponse("limit=${MusicBrainzApi.RECORDING_SEARCH_LIMIT}", NO_RECORDINGS)
        val engine = engine()
        val first = engine.enrich(TRACK, setOf(EnrichmentType.TRACK_METADATA))
        assertTrue(
            "expected the first call to miss, got ${first.raw[EnrichmentType.TRACK_METADATA]}",
            first.raw[EnrichmentType.TRACK_METADATA] is EnrichmentResult.NotFound,
        )

        // When - MusicBrainz starts answering the plain query and the consumer asks for fresh data
        httpClient.givenJsonResponse("limit=${MusicBrainzApi.RECORDING_SEARCH_LIMIT}", RECORDING_SEARCH_HIT)
        val refreshed = engine.enrich(TRACK, setOf(EnrichmentType.TRACK_METADATA), forceRefresh = true)

        // Then - the second call resolves from the new answer. A plain-pool memo living on
        // MusicBrainzApi itself, rather than in ProviderCallScope, would still be holding the first
        // call's empty answer and this would still be NotFound
        assertTrue(
            "expected forceRefresh to reach the corrected upstream answer, got " +
                "${refreshed.raw[EnrichmentType.TRACK_METADATA]}",
            refreshed.raw[EnrichmentType.TRACK_METADATA] is EnrichmentResult.Success,
        )
    }

    private companion object {
        val ALBUM = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        val TRACK = EnrichmentRequest.forTrack("Enter Sandman", "Metallica")

        /** A name MusicBrainz holds no artist under, so every type of a fan-out has to search for it. */
        val ABSENT_ARTIST = EnrichmentRequest.forArtist("Some Unknown Artist Xyzzy")

        /**
         * A title carrying a "(Remastered)" qualifier group, so an absent-track search also runs
         * [com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzQualifierFallback]'s candidates —
         * the searches that must land inside the same memo as the raw search, not outside it.
         */
        val QUALIFIED_TRACK = EnrichmentRequest.forTrack("Paranoid Android (Remastered)", "Radiohead")

        /** A title MusicBrainz stores only under symbols (`F♯ A♯ ∞`), so no spelling searches for it. */
        val SYMBOL_ALBUM = EnrichmentRequest.forAlbum("F# A# (Infinity)", "Godspeed You! Black Emperor")

        const val RELEASE_SEARCH = "release?query"
        const val RELEASE_LOOKUP = "release/thin1"
        const val RELEASE_GROUP = "release-group/group123"
        const val ARTIST_SEARCH = "artist?query"
        const val BROWSE = "release-group?artist="
        const val RECORDING_SEARCH = "recording?query"

        /**
         * The encoded ` AND -comment:*` Lucene term [MusicBrainzApi]'s canonical recording query
         * carries and its plain one does not — the one substring that tells the two searches apart
         * on the wire.
         */
        const val CANONICAL_FILTER_TERM = "-comment%3A"

        /**
         * The encoded `recording:"` term both the canonical and plain searches carry (a quoted,
         * exact title) and [MusicBrainzApi.searchRecordingsFuzzy]'s unquoted `~` search does not —
         * what excludes that third search from a canonical/plain split.
         */
        const val QUOTED_TITLE_TERM = "recording%3A%22"

        /** What MusicBrainz answers a search for an artist name it holds nothing under with. */
        const val NO_ARTISTS = """{"count": 0, "offset": 0, "artists": []}"""

        /**
         * What one absent artist costs in `artist?query=` requests: the strict search, then the
         * fuzzy search that answers the empty pool with suggestions. Spent once for the whole call;
         * a per-type repeat of either is what this number catches.
         */
        const val ARTIST_SEARCHES_PER_ABSENT_ARTIST = 2

        /**
         * What one absent, qualified-title track costs in `recording?query=` requests: the original
         * title's canonical (filtered, deep) and shallow (unfiltered) pools — [QUALIFIED_TRACK]'s
         * trailing `(Remastered)` group makes `searchCanonicalRecordings` union both arms rather
         * than trust either alone — the qualifier fallback's one stripped-title candidate search,
         * and the fuzzy near-miss search an empty suggestion pool asks for.
         *
         * Only **four**, not five: the original title's shallow arm and the unfiltered pool the
         * miss suggests from are the identical URL (same title, same hint-less query), so
         * [com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi]'s plain-pool memo answers both
         * from one upstream fetch. The stripped candidate's title differs, so it stays its own,
         * uncollapsed search — see the per-URL test pinning that.
         *
         * Each is spent once for the call. A memo scoped to the raw search alone leaves the
         * candidate search repeating once per type instead, which is what this number would rise to.
         */
        const val RECORDING_SEARCHES_PER_ABSENT_QUALIFIED_TRACK = 4

        /**
         * What one absent track costs in `recording?query=` requests: the filtered resolution
         * search, the unfiltered pool the empty filtered pool falls back to (also what the miss
         * suggests from), and the fuzzy near-miss search an empty suggestion pool asks for. "Enter
         * Sandman" carries no qualifier group, so the qualifier fallback searches nothing.
         *
         * Only **three**, not four: the unfiltered retry an empty filtered pool falls back to and the
         * unfiltered pool the miss suggests from are the identical URL (same hint-less query, same
         * title), so [com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi]'s plain-pool memo
         * answers both from one upstream fetch instead of two.
         *
         * Each is spent once for the call. A per-type repeat of any of them is what this catches.
         */
        const val RECORDING_SEARCHES_PER_ABSENT_TRACK = 3

        /** What MusicBrainz answers a search for a track it holds no recording of with. */
        const val NO_RECORDINGS = """{"count": 0, "offset": 0, "recordings": []}"""

        /** A recording the ranking resolves, carrying no tags — so it answers TRACK_METADATA and not GENRE. */
        val RECORDING_SEARCH_HIT = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 100,
                "title": "Enter Sandman",
                "length": 331560,
                "artist-credit": [{"artist": {"id": "art-metallica", "name": "Metallica"}}],
                "releases": [{
                  "status": "Official",
                  "release-group": {"id": "rg-studio", "title": "Metallica", "primary-type": "Album"}
                }]
              }]
            }
        """.trimIndent()

        /**
         * What one absent album costs in `release?query=` requests: the ladder's strict search, then
         * the fuzzy search that answers the empty pool with suggestions. [SYMBOL_ALBUM]'s "(Infinity)"
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
              "artist-credit": [{"artist": {"id": "art1", "name": "Radiohead"}}],
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
