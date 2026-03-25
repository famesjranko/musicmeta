package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.*
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.deezer.DeezerApi
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.deezer.SimilarAlbumsProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.fanarttv.FanartTvProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Comprehensive enrichment showcase and diagnostic tool.
 * Exercises every enrichment type across diverse queries, producing a
 * readable report of what works, what's missing, and where to improve.
 *
 * Uses runBlocking (not runTest) because the engine's withTimeout and
 * rate limiter delay() calls require real time, not virtual time.
 *
 * Run all:
 *   ./gradlew :musicmeta-core:test -Dinclude.e2e=true \
 *     --tests "*.EnrichmentShowcaseTest"
 *
 * With API keys (enables Last.fm, Fanart.tv, Discogs):
 *   ./gradlew :musicmeta-core:test -Dinclude.e2e=true \
 *     -Dlastfm.apikey=KEY -Dfanarttv.apikey=KEY -Ddiscogs.token=TOKEN \
 *     --tests "*.EnrichmentShowcaseTest"
 *
 * Or set env vars: LASTFM_API_KEY, FANARTTV_API_KEY, DISCOGS_TOKEN
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EnrichmentShowcaseTest {

    private lateinit var engine: EnrichmentEngine

    @Before
    fun setup() {
        Assume.assumeTrue(
            "E2E tests disabled. Run with -Dinclude.e2e=true",
            System.getProperty("include.e2e") == "true",
        )

        val f = E2ETestFixture
        val deezerApi = DeezerApi(f.httpClient, f.defaultRateLimiter)
        engine = EnrichmentEngine.Builder()
            .addProvider(MusicBrainzProvider(f.httpClient, f.mbRateLimiter))
            .addProvider(CoverArtArchiveProvider(f.httpClient, f.defaultRateLimiter))
            .addProvider(WikidataProvider(f.httpClient, f.defaultRateLimiter))
            .addProvider(WikipediaProvider(f.httpClient, f.defaultRateLimiter))
            .addProvider(LrcLibProvider(f.httpClient, f.lrcLibRateLimiter))
            .addProvider(DeezerProvider(f.httpClient, f.defaultRateLimiter))
            .addProvider(SimilarAlbumsProvider(deezerApi))
            .addProvider(ITunesProvider(f.httpClient, f.itunesRateLimiter))
            .addProvider(ListenBrainzProvider(f.httpClient, f.defaultRateLimiter))
            .addProvider(LastFmProvider(f.prop("lastfm.apikey"), f.httpClient, f.lastFmRateLimiter))
            .addProvider(FanartTvProvider(f.prop("fanarttv.apikey"), f.httpClient, f.defaultRateLimiter))
            .addProvider(DiscogsProvider(f.prop("discogs.token"), f.httpClient, f.defaultRateLimiter))
            .build()
    }

    // --- 1. Provider availability ---

    @Test
    fun `01 - provider availability report`() {
        banner("PROVIDER AVAILABILITY")
        val providers = engine.getProviders()
        providers.forEach { p ->
            val status = if (p.isAvailable) "ACTIVE" else "MISSING KEY"
            val types = p.capabilities.joinToString(", ") { it.type.name }
            println("  %-16s %-12s %s".format(p.displayName, status, types))
        }
        val active = providers.count { it.isAvailable }
        println("\n  $active/${providers.size} providers active")
    }

    // --- 2. Artist deep dive ---

    @Test
    fun `02 - artist deep dive - Radiohead`() = runBlocking {
        banner("ARTIST: Radiohead")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            ALL_TYPES,
        )
        printResults(results)
    }

    // --- 3. Album deep dive ---

    @Test
    fun `03 - album deep dive - OK Computer`() = runBlocking {
        banner("ALBUM: OK Computer by Radiohead")
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            ALL_TYPES,
        )
        printResults(results)
    }

    // --- 4. Track deep dive ---

    @Test
    fun `04 - track deep dive - Bohemian Rhapsody`() = runBlocking {
        banner("TRACK: Bohemian Rhapsody by Queen")
        val results = engine.enrich(
            EnrichmentRequest.forTrack(
                "Bohemian Rhapsody", "Queen",
                album = "A Night at the Opera",
            ),
            ALL_TYPES,
        )
        printResults(results)
    }

    // --- 5. Cross-genre: modern hip-hop ---

    @Test
    fun `05 - cross-genre - Kendrick Lamar + DAMN`() = runBlocking {
        banner("CROSS-GENRE: Kendrick Lamar (hip-hop)")
        val artistResults = engine.enrich(
            EnrichmentRequest.forArtist("Kendrick Lamar"),
            setOf(
                EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BIO,
                EnrichmentType.GENRE, EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentType.ARTIST_POPULARITY,
            ),
        )
        printResults(artistResults)

        println("\n  --- Album: DAMN. ---")
        val albumResults = engine.enrich(
            EnrichmentRequest.forAlbum("DAMN.", "Kendrick Lamar"),
            setOf(
                EnrichmentType.ALBUM_ART, EnrichmentType.GENRE,
                EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE,
            ),
        )
        printResults(albumResults)
    }

    // --- 6. Edge cases ---

    @Test
    fun `06 - edge cases`() = runBlocking {
        banner("EDGE CASES")

        println("  --- AC/DC (slash in name) ---")
        printResults(engine.enrich(
            EnrichmentRequest.forArtist("AC/DC"),
            setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO),
        ))

        println("\n  --- Bjork (non-ASCII) ---")
        printResults(engine.enrich(
            EnrichmentRequest.forArtist("Björk"),
            setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_PHOTO),
        ))

        println("\n  --- Instrumental: Orion by Metallica ---")
        printResults(engine.enrich(
            EnrichmentRequest.forTrack("Orion", "Metallica", album = "Master of Puppets"),
            setOf(EnrichmentType.LYRICS_SYNCED, EnrichmentType.LYRICS_PLAIN),
        ))

        println("\n  --- Obscure: The Gerogerigegege ---")
        printResults(engine.enrich(
            EnrichmentRequest.forArtist("The Gerogerigegege"),
            setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BIO, EnrichmentType.GENRE),
        ))

        println("\n  --- Electronic: Random Access Memories by Daft Punk ---")
        printResults(engine.enrich(
            EnrichmentRequest.forAlbum("Random Access Memories", "Daft Punk"),
            setOf(
                EnrichmentType.ALBUM_ART, EnrichmentType.GENRE,
                EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE,
            ),
        ))
    }

    // --- 7. Search disambiguation ---

    @Test
    fun `07 - search disambiguation`() = runBlocking {
        banner("SEARCH DISAMBIGUATION")

        println("  --- Track: 'Yesterday' by The Beatles ---")
        engine.search(EnrichmentRequest.forTrack("Yesterday", "The Beatles"), limit = 5)
            .forEach { c ->
                println("    ${c.title} by ${c.artist} (${c.year}) score=${c.score}")
            }

        println("\n  --- Artist: 'Air' (ambiguous) ---")
        engine.search(EnrichmentRequest.forArtist("Air"), limit = 5)
            .forEach { c ->
                println("    ${c.title} (${c.country}, ${c.releaseType}) score=${c.score}")
            }

        println("\n  --- Album: 'Homesick' (multiple artists) ---")
        engine.search(EnrichmentRequest.forAlbum("Homesick", "A Day to Remember"), limit = 5)
            .forEach { c ->
                println("    ${c.title} by ${c.artist} (${c.year}) score=${c.score}")
            }
    }

    // --- 8. Coverage matrix ---

    @Test
    fun `08 - coverage matrix`() {
        banner("COVERAGE MATRIX (v0.8.0)")
        val providers = engine.getProviders()
        var multiCount = 0
        EnrichmentType.entries.forEach { type ->
            val supporting = providers.filter { p ->
                p.capabilities.any { it.type == type }
            }
            if (supporting.size >= 2) multiCount++
            val marker = when {
                supporting.size >= 3 -> "M+"
                supporting.size == 2 -> "M "
                supporting.size == 1 -> "S "
                else -> "- "
            }
            val providerList = supporting.joinToString(", ") { p ->
                val cap = p.capabilities.first { it.type == type }
                "${p.displayName}(${cap.priority})"
            }
            println("  $marker %-22s %s".format(type.name, providerList))
        }
        println("\n  M+/M = multi-provider, S = single provider, - = no provider")
        println("  $multiCount/${EnrichmentType.entries.size} types have multi-provider coverage")
        println("\n  ENGINE FEATURES (v0.8.0):")
        println("    - GENRE uses GenreMerger (multi-provider merge, not short-circuit)")
        println("    - SIMILAR_ARTISTS uses SimilarArtistMerger (Last.fm + ListenBrainz + Deezer)")
        println("    - ARTIST_TIMELINE is composite (auto-resolves DISCOGRAPHY + BAND_MEMBERS)")
        println("    - GENRE_DISCOVERY is composite (GenreAffinityMatcher via static taxonomy)")
        println("    - ARTIST_RADIO backed by Deezer /artist/{id}/radio (7-day TTL)")
        println("    - SIMILAR_ALBUMS synthesized from similar artists + era scoring (Deezer)")
        println("    - CatalogProvider interface: plug in your library for AVAILABLE_ONLY/AVAILABLE_FIRST filtering")
        println("    - All 11 providers use HttpResult/ErrorKind uniformly")
        println("    - v0.8.0: OkHttpEnrichmentClient adapter (musicmeta-okhttp module)")
        println("    - v0.8.0: CacheMode.STALE_IF_ERROR — offline fallback with isStale flag")
        println("    - v0.8.0: enrichBatch() Flow API for bulk enrichment")
        println("    - v0.8.0: Maven Central publishing via vanniktech + Central Portal")
    }

    // --- 9. v0.5.0 feature spotlight ---

    @Test
    fun `09 - v0_5_0 feature spotlight`() = runBlocking {
        banner("v0.5.0 FEATURES")

        // Credits: Bohemian Rhapsody (well-known credits)
        println("  --- CREDITS: Bohemian Rhapsody by Queen ---")
        val creditResults = engine.enrich(
            EnrichmentRequest.forTrack("Bohemian Rhapsody", "Queen", album = "A Night at the Opera"),
            setOf(EnrichmentType.CREDITS),
        )
        val credits = creditResults.raw[EnrichmentType.CREDITS]
        if (credits is EnrichmentResult.Success) {
            println("    provider: ${credits.provider}, conf=${credits.confidence}")
            val data = credits.data as EnrichmentData.Credits
            val byCategory = data.credits.groupBy { it.roleCategory ?: "other" }
            byCategory.forEach { (category, members) ->
                println("    [$category]")
                members.forEach { println("      ${it.name} — ${it.role}") }
            }
        } else printSingleResult(EnrichmentType.CREDITS, credits)

        // Release Editions: OK Computer (many pressings)
        println("\n  --- RELEASE EDITIONS: OK Computer by Radiohead ---")
        val editionResults = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.RELEASE_EDITIONS),
        )
        val editions = editionResults.raw[EnrichmentType.RELEASE_EDITIONS]
        if (editions is EnrichmentResult.Success) {
            println("    provider: ${editions.provider}, conf=${editions.confidence}")
            val data = editions.data as EnrichmentData.ReleaseEditions
            println("    ${data.editions.size} editions found")
            data.editions.take(8).forEach { e ->
                println("    - %-30s %-8s %-4s %s".format(
                    e.title.take(30), e.format ?: "-", e.country ?: "-", e.year ?: "-",
                ))
            }
            if (data.editions.size > 8) println("    ... and ${data.editions.size - 8} more")
        } else printSingleResult(EnrichmentType.RELEASE_EDITIONS, editions)

        // Artist Timeline: Radiohead
        println("\n  --- ARTIST TIMELINE: Radiohead ---")
        val timelineResults = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.ARTIST_TIMELINE),
        )
        val timeline = timelineResults.raw[EnrichmentType.ARTIST_TIMELINE]
        if (timeline is EnrichmentResult.Success) {
            println("    provider: ${timeline.provider}, conf=${timeline.confidence}")
            val data = timeline.data as EnrichmentData.ArtistTimeline
            println("    ${data.events.size} events")
            data.events.forEach { e ->
                val entity = e.relatedEntity?.let { " ($it)" } ?: ""
                println("    %-12s %-18s %s%s".format(e.date, e.type, e.description.take(50), entity))
            }
        } else printSingleResult(EnrichmentType.ARTIST_TIMELINE, timeline)

        // Genre merge: Radiohead
        println("\n  --- GENRE MERGE: Radiohead ---")
        val genreResults = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.GENRE),
        )
        val genre = genreResults.raw[EnrichmentType.GENRE]
        if (genre is EnrichmentResult.Success) {
            println("    provider: ${genre.provider}, conf=${genre.confidence}")
            val data = genre.data as EnrichmentData.Metadata
            data.genreTags?.forEach { tag ->
                println("    %-28s conf=%.2f  sources=%s".format(tag.name, tag.confidence, tag.sources))
            } ?: println("    genres: ${data.genres}")
            println("    backward-compat genres: ${data.genres?.take(5)}")
        } else printSingleResult(EnrichmentType.GENRE, genre)
    }

    // --- 10. v0.6.0 feature spotlight ---

    @Test
    fun `10 - v0_6_0 feature spotlight`() = runBlocking {
        banner("v0.6.0 FEATURES: RECOMMENDATIONS ENGINE")

        // SIMILAR_ARTISTS merge: Radiohead — should show sources=[lastfm, listenbrainz, deezer]
        println("  --- SIMILAR_ARTISTS MERGE: Radiohead ---")
        val similarArtistResults = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val similarArtists = similarArtistResults.raw[EnrichmentType.SIMILAR_ARTISTS]
        if (similarArtists is EnrichmentResult.Success) {
            println("    provider: ${similarArtists.provider}, conf=${similarArtists.confidence}")
            val data = similarArtists.data as EnrichmentData.SimilarArtists
            println("    ${data.artists.size} artists (merged)")
            data.artists.take(6).forEach { a ->
                println("    %-28s score=%.2f  sources=%s".format(a.name, a.matchScore, a.sources))
            }
            val multiSource = data.artists.count { it.sources.size > 1 }
            println("    $multiSource/${data.artists.size} artists contributed by 2+ providers")
        } else printSingleResult(EnrichmentType.SIMILAR_ARTISTS, similarArtists)

        // ARTIST_RADIO: Daft Punk — Deezer radio tracks
        println("\n  --- ARTIST_RADIO: Daft Punk ---")
        val radioResults = engine.enrich(
            EnrichmentRequest.forArtist("Daft Punk"),
            setOf(EnrichmentType.ARTIST_RADIO),
        )
        val radio = radioResults.raw[EnrichmentType.ARTIST_RADIO]
        if (radio is EnrichmentResult.Success) {
            println("    provider: ${radio.provider}, conf=${radio.confidence}")
            val data = radio.data as EnrichmentData.RadioPlaylist
            println("    ${data.tracks.size} tracks")
            data.tracks.take(5).forEach { t ->
                val dur = t.durationMs?.let { "${it / 1000}s" } ?: "?"
                println("    %-30s %-20s %s".format(t.title.take(30), t.artist.take(20), dur))
            }
        } else printSingleResult(EnrichmentType.ARTIST_RADIO, radio)

        // SIMILAR_ALBUMS: OK Computer — scored by artist similarity + era
        println("\n  --- SIMILAR_ALBUMS: OK Computer by Radiohead ---")
        val albumResults = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        val albums = albumResults.raw[EnrichmentType.SIMILAR_ALBUMS]
        if (albums is EnrichmentResult.Success) {
            println("    provider: ${albums.provider}, conf=${albums.confidence}")
            val data = albums.data as EnrichmentData.SimilarAlbums
            println("    ${data.albums.size} similar albums")
            data.albums.take(6).forEach { a ->
                val year = a.year?.toString() ?: "?"
                println("    %-30s %-20s %s  score=%.2f".format(
                    a.title.take(30), a.artist.take(20), year, a.artistMatchScore,
                ))
            }
        } else printSingleResult(EnrichmentType.SIMILAR_ALBUMS, albums)

        // GENRE_DISCOVERY: Radiohead — affinity-scored genre neighbors
        println("\n  --- GENRE_DISCOVERY: Radiohead ---")
        val genreDiscoveryResults = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        val genreDiscovery = genreDiscoveryResults.raw[EnrichmentType.GENRE_DISCOVERY]
        if (genreDiscovery is EnrichmentResult.Success) {
            println("    provider: ${genreDiscovery.provider}, conf=${genreDiscovery.confidence}")
            val data = genreDiscovery.data as EnrichmentData.GenreDiscovery
            println("    ${data.relatedGenres.size} related genres")
            data.relatedGenres.take(8).forEach { g ->
                println("    %-28s affinity=%.2f  rel=%-8s sources=%s".format(
                    g.name, g.affinity, g.relationship, g.sourceGenres,
                ))
            }
        } else printSingleResult(EnrichmentType.GENRE_DISCOVERY, genreDiscovery)
    }

    // --- 11. v0.7.0 feature spotlight ---

    @Test
    fun `11 - v0_7_0 feature spotlight`() = runBlocking {
        banner("v0.7.0 FEATURES: DEVELOPER EXPERIENCE")

        // Tier 1: Profile methods — one call, structured result
        println("  --- TIER 1: artistProfile() ---")
        val profile = engine.artistProfile("Radiohead")
        println("    Name: ${profile.name}")
        println("    Identity: ${profile.identityMatch} (score=${profile.identityMatchScore})")
        println("    MBID: ${profile.identifiers.musicBrainzId}")
        println("    Photo: ${profile.photo?.url?.take(60) ?: "none"}${if ((profile.photo?.url?.length ?: 0) > 60) "..." else ""}")
        println("    Bio: ${profile.bio?.text?.take(80) ?: "none"}...")
        println("    Genres: ${profile.genres.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.confidence) }}")
        println("    Country: ${profile.country}")
        println("    Members: ${profile.members.take(3).joinToString(", ") { it.name }}")
        println("    Discography: ${profile.discography.size} albums")
        println("    Similar: ${profile.similarArtists?.artists?.take(3)?.joinToString(", ") { it.name } ?: "none"}")
        println("    Top tracks: ${profile.topTracks?.tracks?.take(3)?.joinToString(", ") { it.title } ?: "none"}")

        println("\n  --- TIER 1: albumProfile() ---")
        val albumProf = engine.albumProfile("OK Computer", "Radiohead")
        println("    Title: ${albumProf.title} by ${albumProf.artist}")
        println("    Artwork: ${albumProf.artwork?.url?.take(60) ?: "none"}")
        println("    Genres: ${albumProf.genres.take(3).joinToString(", ") { it.name }}")
        println("    Label: ${albumProf.label}")
        println("    Release date: ${albumProf.releaseDate}")
        println("    Tracks: ${albumProf.tracks.size}")

        println("\n  --- TIER 1: trackProfile() ---")
        val trackProf = engine.trackProfile("Creep", "Radiohead")
        println("    Title: ${trackProf.title} by ${trackProf.artist}")
        println("    Genres: ${trackProf.genres.take(3).joinToString(", ") { it.name }}")
        val lyricsData = trackProf.lyrics
        println("    Lyrics: ${if (lyricsData != null) "${lyricsData.plainLyrics?.lines()?.size ?: 0} lines" else "none"}")

        // Tier 2: EnrichmentResults named accessors
        println("\n  --- TIER 2: Named accessors on EnrichmentResults ---")
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(
                EnrichmentType.ALBUM_ART, EnrichmentType.GENRE,
                EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE,
                EnrichmentType.COUNTRY, EnrichmentType.ALBUM_METADATA,
            ),
        )
        println("    albumArt(): ${results.albumArt()?.url?.take(50) ?: "null"}")
        println("    genres(): ${results.genres().take(4)}")
        println("    genreTags(): ${results.genreTags().take(3).joinToString(", ") { "${it.name}(${it.confidence})" }}")
        println("    label(): ${results.label()}")
        println("    releaseDate(): ${results.releaseDate()}")
        println("    country(): ${results.country()}")
        println("    releaseType(): ${results.releaseType()}")

        // wasRequested() / result() diagnostics
        println("\n  --- DIAGNOSTICS: wasRequested() + result() ---")
        println("    wasRequested(ALBUM_ART): ${results.wasRequested(EnrichmentType.ALBUM_ART)}")
        println("    wasRequested(LYRICS_SYNCED): ${results.wasRequested(EnrichmentType.LYRICS_SYNCED)}")
        val artResult = results.result(EnrichmentType.ALBUM_ART)
        println("    result(ALBUM_ART): ${artResult?.javaClass?.simpleName} provider=${(artResult as? EnrichmentResult.Success)?.provider}")

        // Identity resolution on results
        println("\n  --- IDENTITY RESOLUTION ---")
        val identity = results.identity
        println("    match: ${identity?.match}")
        println("    matchScore: ${identity?.matchScore}")
        println("    MBID: ${identity?.identifiers?.musicBrainzId}")
        println("    suggestions: ${identity?.suggestions?.size ?: 0}")

        // Default type sets
        println("\n  --- DEFAULT TYPE SETS ---")
        println("    DEFAULT_ARTIST_TYPES: ${EnrichmentRequest.DEFAULT_ARTIST_TYPES.size} types")
        println("      ${EnrichmentRequest.DEFAULT_ARTIST_TYPES.joinToString(", ") { it.name }}")
        println("    DEFAULT_ALBUM_TYPES: ${EnrichmentRequest.DEFAULT_ALBUM_TYPES.size} types")
        println("      ${EnrichmentRequest.DEFAULT_ALBUM_TYPES.joinToString(", ") { it.name }}")
        println("    DEFAULT_TRACK_TYPES: ${EnrichmentRequest.DEFAULT_TRACK_TYPES.size} types")
        println("      ${EnrichmentRequest.DEFAULT_TRACK_TYPES.joinToString(", ") { it.name }}")
        println("    defaultTypesFor(ForArtist) == DEFAULT_ARTIST_TYPES: ${EnrichmentRequest.defaultTypesFor(EnrichmentRequest.forArtist("test")) == EnrichmentRequest.DEFAULT_ARTIST_TYPES}")

        // forceRefresh
        println("\n  --- FORCE REFRESH ---")
        val freshProfile = engine.artistProfile("Radiohead",
            types = setOf(EnrichmentType.GENRE),
            forceRefresh = true,
        )
        println("    forceRefresh=true: genres=${freshProfile.genres.take(2).joinToString(", ") { it.name }}")

        // invalidate
        println("\n  --- INVALIDATE ---")
        val req = EnrichmentRequest.forArtist("Radiohead")
        engine.invalidate(req, EnrichmentType.GENRE)
        println("    invalidate(Radiohead, GENRE): done")
        engine.invalidate(req)
        println("    invalidate(Radiohead, all types): done")

        // Manual selection
        println("\n  --- MANUAL SELECTION ---")
        val photoReq = EnrichmentRequest.forArtist("Radiohead")
        val wasBefore = engine.isManuallySelected(photoReq, EnrichmentType.ARTIST_PHOTO)
        engine.markManuallySelected(photoReq, EnrichmentType.ARTIST_PHOTO)
        val wasAfter = engine.isManuallySelected(photoReq, EnrichmentType.ARTIST_PHOTO)
        println("    isManuallySelected before: $wasBefore, after markManuallySelected: $wasAfter")
    }

    // --- 12. v0.8.0 feature spotlight ---

    @Test
    fun `12 - v0_8_0 feature spotlight`() = runBlocking {
        banner("v0.8.0 FEATURES: PRODUCTION READINESS")
        println("  (OkHttp adapter E2E tested in musicmeta-okhttp module)")

        // Stale-while-revalidate cache: demonstrate isStale flag
        println("\n  --- STALE CACHE (STALE_IF_ERROR) ---")
        val staleConfig = com.landofoz.musicmeta.EnrichmentConfig(
            cacheMode = com.landofoz.musicmeta.cache.CacheMode.STALE_IF_ERROR,
        )
        val staleEngine = EnrichmentEngine.Builder()
            .config(staleConfig)
            .withDefaultProviders()
            .build()
        // First: populate cache with a real result
        val warmup = staleEngine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )
        val warmupGenre = warmup.raw[EnrichmentType.GENRE]
        println("    Cache warm: GENRE ${if (warmupGenre is EnrichmentResult.Success) "cached (isStale=${warmupGenre.isStale})" else "not cached"}")

        // Second: same request, should come from cache (isStale=false because cache is fresh)
        val cached = staleEngine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.GENRE),
        )
        val cachedGenre = cached.raw[EnrichmentType.GENRE]
        println("    Cache hit: GENRE ${if (cachedGenre is EnrichmentResult.Success) "isStale=${cachedGenre.isStale}" else "miss"}")

        // enrichBatch: demonstrate Flow-based bulk enrichment
        println("\n  --- BULK ENRICHMENT (enrichBatch) ---")
        val albums = listOf(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            EnrichmentRequest.forAlbum("The Bends", "Radiohead"),
            EnrichmentRequest.forAlbum("Kid A", "Radiohead"),
        )
        var batchCount = 0
        engine.enrichBatch(albums, setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_ART)).collect { (request, results) ->
            batchCount++
            val genreStatus = when (val g = results.raw[EnrichmentType.GENRE]) {
                is EnrichmentResult.Success -> "SUCCESS (${g.provider})"
                is EnrichmentResult.NotFound -> "NotFound"
                else -> g?.javaClass?.simpleName ?: "null"
            }
            val artStatus = when (val a = results.raw[EnrichmentType.ALBUM_ART]) {
                is EnrichmentResult.Success -> "SUCCESS (${a.provider})"
                is EnrichmentResult.NotFound -> "NotFound"
                else -> a?.javaClass?.simpleName ?: "null"
            }
            val label = (request as? EnrichmentRequest.ForAlbum)?.title ?: request.toString()
            println("    [$batchCount/3] $label: GENRE=$genreStatus, ART=$artStatus")
        }
        println("    Batch complete: $batchCount/${albums.size} results emitted")

        // Maven Central publishing status
        println("\n  --- MAVEN CENTRAL PUBLISHING ---")
        println("    Version: 0.8.0")
        println("    Modules: musicmeta-core, musicmeta-okhttp, musicmeta-android")
        println("    Plugin: com.vanniktech.maven.publish (Central Portal)")
        println("    publishToMavenLocal: verified (dry-run)")
    }

    // --- Helpers ---

    private fun banner(title: String) {
        println("\n${"=".repeat(64)}")
        println("  $title")
        println("=".repeat(64))
    }

    private fun printResults(results: EnrichmentResults) {
        val identityResult = results.raw.values
            .filterIsInstance<EnrichmentResult.Success>()
            .firstOrNull { it.resolvedIdentifiers != null }
        if (identityResult != null) {
            val ids = identityResult.resolvedIdentifiers
            println("  Identity: mbid=${ids?.musicBrainzId ?: "-"}" +
                " wikidata=${ids?.wikidataId ?: "-"}" +
                " wikipedia=${ids?.wikipediaTitle ?: "-"}" +
                " conf=${identityResult.confidence}")
        }

        var found = 0; var missing = 0; var errors = 0
        // Iterate in canonical order but only show types present in results
        ALL_TYPES.filter { it in results.raw }.forEach { type ->
            val result = results.raw[type]
            val line = when (result) {
                is EnrichmentResult.Success -> {
                    found++
                    "  + %-20s %-14s conf=%.2f  %s".format(
                        type.name, result.provider, result.confidence, snippet(result.data),
                    )
                }
                is EnrichmentResult.NotFound -> {
                    missing++
                    "  - %-20s %-14s not found".format(type.name, result.provider)
                }
                is EnrichmentResult.RateLimited -> {
                    errors++
                    "  ! %-20s %-14s rate limited".format(type.name, result.provider)
                }
                is EnrichmentResult.Error -> {
                    errors++
                    "  ! %-20s %-14s ERROR(%s): %s".format(
                        type.name, result.provider, result.errorKind, result.message.take(50),
                    )
                }
                null -> {
                    missing++
                    "  . %-20s %-14s no provider".format(type.name, "-")
                }
            }
            println(line)
        }
        println("  --- $found found, $missing missing, $errors errors ---")
    }

    private fun snippet(data: EnrichmentData): String = when (data) {
        is EnrichmentData.Artwork ->
            "url=${data.url.take(70)}${if (data.url.length > 70) "..." else ""}"
        is EnrichmentData.Metadata -> listOfNotNull(
            data.genreTags?.let { tags ->
                "tags=${tags.take(3).joinToString(", ") { "${it.name}(%.2f,${it.sources})".format(it.confidence) }}"
            } ?: data.genres?.let { "genres=${it.take(4)}" },
            data.label?.let { "label=$it" },
            data.releaseDate, data.releaseType, data.country,
        ).joinToString(" | ")
        is EnrichmentData.Lyrics -> buildString {
            if (data.isInstrumental) append("[instrumental] ")
            data.syncedLyrics?.let { append("synced=${it.lines().size}lines ") }
            data.plainLyrics?.let { append("plain=${it.lines().size}lines") }
        }
        is EnrichmentData.Biography ->
            "\"${data.text.take(80)}...\""
        is EnrichmentData.SimilarArtists ->
            data.artists.take(4).joinToString(", ") { "${it.name}(%.1f)".format(it.matchScore) }
        is EnrichmentData.Popularity -> buildString {
            data.listenerCount?.let { append("listeners=$it ") }
            data.listenCount?.let { append("plays=$it ") }
            data.topTracks?.firstOrNull()?.let { append("top: ${it.title}(${it.listenCount})") }
        }
        is EnrichmentData.BandMembers ->
            data.members.take(4).joinToString(", ") { it.name }
        is EnrichmentData.Discography ->
            "${data.albums.size} albums"
        is EnrichmentData.Tracklist ->
            "${data.tracks.size} tracks"
        is EnrichmentData.SimilarTracks ->
            data.tracks.take(4).joinToString(", ") { "${it.title}(%.1f)".format(it.matchScore) }
        is EnrichmentData.ArtistLinks ->
            data.links.take(4).joinToString(", ") { "${it.type}=${it.url.take(40)}" }
        is EnrichmentData.Credits -> buildString {
            val byCategory = data.credits.groupBy { it.roleCategory ?: "other" }
            append(byCategory.entries.joinToString(", ") { "${it.value.size} ${it.key}" })
            append(" | ")
            append(data.credits.take(4).joinToString(", ") { "${it.name}(${it.role})" })
        }
        is EnrichmentData.ReleaseEditions -> buildString {
            append("${data.editions.size} editions")
            val formats = data.editions.mapNotNull { it.format }.distinct().take(4)
            if (formats.isNotEmpty()) append(" formats=$formats")
            val countries = data.editions.mapNotNull { it.country }.distinct().take(4)
            if (countries.isNotEmpty()) append(" countries=$countries")
        }
        is EnrichmentData.ArtistTimeline -> buildString {
            val byType = data.events.groupBy { it.type }
            append("${data.events.size} events (${byType.entries.joinToString(", ") { "${it.value.size} ${it.key}" }})")
            data.events.firstOrNull()?.let { append(" first=${it.date}:${it.type}") }
        }
        is EnrichmentData.RadioPlaylist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarAlbums ->
            "${data.albums.size} similar albums: ${data.albums.take(3).joinToString(", ") { "${it.title} by ${it.artist}" }}"
        is EnrichmentData.GenreDiscovery ->
            "${data.relatedGenres.size} related genres: ${data.relatedGenres.take(3).joinToString(", ") { "${it.name}(%.2f)".format(it.affinity) }}"
        is EnrichmentData.TopTracks ->
            "${data.tracks.size} top tracks: ${data.tracks.take(3).joinToString(", ") { "${it.title}(${it.listenCount ?: 0})" }}"
        is EnrichmentData.TrackPreview ->
            "url=${data.url.take(70)} duration=${data.durationMs}ms source=${data.source}"
    }

    private fun printSingleResult(type: EnrichmentType, result: EnrichmentResult?) {
        val line = when (result) {
            is EnrichmentResult.Success -> "    SUCCESS (${result.provider}, conf=${result.confidence})"
            is EnrichmentResult.NotFound -> "    NOT FOUND (${result.provider})"
            is EnrichmentResult.RateLimited -> "    RATE LIMITED (${result.provider})"
            is EnrichmentResult.Error -> "    ERROR(${result.errorKind}): ${result.message.take(60)}"
            null -> "    NO PROVIDER for ${type.name}"
        }
        println(line)
    }

    companion object {
        private const val USER_AGENT = E2ETestFixture.USER_AGENT
        private val ALL_TYPES = EnrichmentType.entries.toSet()
    }
}
