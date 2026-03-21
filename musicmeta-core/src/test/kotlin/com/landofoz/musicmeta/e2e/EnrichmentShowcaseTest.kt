package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.fanarttv.FanartTvProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider
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

        val http = DefaultHttpClient(USER_AGENT)
        engine = EnrichmentEngine.Builder()
            .addProvider(MusicBrainzProvider(http, RateLimiter(1100)))
            .addProvider(CoverArtArchiveProvider(http, RateLimiter(100)))
            .addProvider(WikidataProvider(http, RateLimiter(100)))
            .addProvider(WikipediaProvider(http, RateLimiter(100)))
            .addProvider(LrcLibProvider(http, RateLimiter(200)))
            .addProvider(DeezerProvider(http, RateLimiter(100)))
            .addProvider(ITunesProvider(http, RateLimiter(3000)))
            .addProvider(ListenBrainzProvider(http, RateLimiter(100)))
            .addProvider(LastFmProvider(prop("lastfm.apikey"), http, RateLimiter(200)))
            .addProvider(FanartTvProvider(prop("fanarttv.apikey"), http, RateLimiter(100)))
            .addProvider(DiscogsProvider(prop("discogs.token"), http, RateLimiter(100)))
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

    // --- 8. Coverage gaps ---

    @Test
    fun `08 - coverage gaps and wishlist`() {
        banner("COVERAGE GAPS & WISHLIST")
        println("""
  CURRENT types (${EnrichmentType.entries.size}):
    Artwork:       ALBUM_ART, ALBUM_ART_BACK, ALBUM_BOOKLET, ARTIST_PHOTO,
                   ARTIST_BACKGROUND, ARTIST_LOGO, ARTIST_BANNER, CD_ART
    Metadata:      GENRE, LABEL, RELEASE_DATE, RELEASE_TYPE, COUNTRY,
                   BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, ALBUM_METADATA
    Text:          ARTIST_BIO, LYRICS_SYNCED, LYRICS_PLAIN
    Relationships: SIMILAR_ARTISTS, SIMILAR_TRACKS, ARTIST_LINKS
    Stats:         ARTIST_POPULARITY, TRACK_POPULARITY

  MULTI-PROVIDER COVERAGE (v0.4.0):
    - ALBUM_ART          -> CAA, Fanart.tv, Deezer, iTunes (all with multi-size)
    - ARTIST_PHOTO       -> Wikidata, Fanart.tv, Wikipedia (page media-list)
    - BAND_MEMBERS       -> MusicBrainz (artist-rels), Discogs (artist endpoint)
    - ARTIST_DISCOGRAPHY -> MusicBrainz (browse release-groups), Deezer (artist albums)
    - ALBUM_TRACKS       -> MusicBrainz (media array), Deezer (album tracks)
    - ALBUM_METADATA     -> Deezer, Discogs, iTunes (trackCount, label, genres)
    - TRACK_POPULARITY   -> Last.fm (track.getInfo), ListenBrainz (batch recording)
    - ARTIST_POPULARITY  -> Last.fm, ListenBrainz (batch artist)

  THIN COVERAGE (single provider, often behind API key):
    - ARTIST_BACKGROUND  -> Fanart.tv only (needs key + MBID)
    - ARTIST_LOGO        -> Fanart.tv only (needs key + MBID)
    - ARTIST_BANNER      -> Fanart.tv only (needs key + MBID)
    - CD_ART             -> Fanart.tv only (needs key + MBID)
    - SIMILAR_ARTISTS    -> Last.fm only (needs key)
    - SIMILAR_TRACKS     -> Last.fm only (track.getSimilar)

  NOT YET IMPLEMENTED (wishlist):
    - CREDITS            -> producers, performers, composers (MusicBrainz, Discogs)
    - RELEASE_EDITIONS   -> all editions/pressings of an album (MusicBrainz)
    - ARTIST_TIMELINE    -> formed, albums, member changes, hiatus, reunion
    - GENRE_DEEP_DIVE    -> merged/deduplicated tags with confidence scoring

  TECH DEBT (v0.4.0):
    - ErrorKind enum exists but no provider categorizes errors yet
    - HttpResult/fetchJsonResult() exists but no provider uses it yet
    - ListenBrainz ARTIST_DISCOGRAPHY plumbing exists but not wired
        """.trimIndent())
    }

    // --- Helpers ---

    private fun banner(title: String) {
        println("\n${"=".repeat(64)}")
        println("  $title")
        println("=".repeat(64))
    }

    private fun printResults(results: Map<EnrichmentType, EnrichmentResult>) {
        val identityResult = results.values
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
        ALL_TYPES.forEach { type ->
            val result = results[type]
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
                    "  ! %-20s %-14s ERROR: %s".format(
                        type.name, result.provider, result.message.take(50),
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
            data.genres?.let { "genres=${it.take(4)}" },
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
        is EnrichmentData.Credits ->
            data.credits.take(4).joinToString(", ") { "${it.name}(${it.role})" }
    }

    companion object {
        private const val USER_AGENT =
            "MusicMetaShowcase/1.0 (https://github.com/famesjranko/musicmeta)"

        private val ALL_TYPES = EnrichmentType.entries.toSet()

        private fun prop(key: String) = System.getProperty(key, "")
    }
}
