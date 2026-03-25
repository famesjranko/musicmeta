package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
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
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Edge case analysis and behavior measurement.
 * Produces a diagnostic report of system behavior at boundaries.
 *
 * Run: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*.EdgeAnalysisTest"
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EdgeAnalysisTest {

    companion object {
        private lateinit var engine: EnrichmentEngine
        private val report = mutableListOf<String>()

        @BeforeClass
        @JvmStatic
        fun setup() {
            Assume.assumeTrue(System.getProperty("include.e2e") == "true")
            val f = E2ETestFixture
            engine = EnrichmentEngine.Builder()
                .addProvider(MusicBrainzProvider(f.httpClient, f.mbRateLimiter))
                .addProvider(CoverArtArchiveProvider(f.httpClient, f.defaultRateLimiter))
                .addProvider(WikidataProvider(f.httpClient, f.defaultRateLimiter))
                .addProvider(WikipediaProvider(f.httpClient, f.defaultRateLimiter))
                .addProvider(LrcLibProvider(f.httpClient, f.lrcLibRateLimiter))
                .addProvider(DeezerProvider(f.httpClient, f.defaultRateLimiter))
                .addProvider(ITunesProvider(f.httpClient, f.itunesRateLimiter))
                .addProvider(ListenBrainzProvider(f.httpClient, f.defaultRateLimiter))
                .addProvider(LastFmProvider(f.prop("lastfm.apikey"), f.httpClient, f.lastFmRateLimiter))
                .addProvider(FanartTvProvider(f.prop("fanarttv.apikey"), f.httpClient, f.defaultRateLimiter))
                .addProvider(DiscogsProvider(f.prop("discogs.token"), f.httpClient, f.defaultRateLimiter))
                .build()
        }

        fun log(s: String) {
            println(s)
            report.add(s)
        }
    }

    private fun banner(title: String) {
        log("\n  ================================================================")
        log("    $title")
        log("  ================================================================")
    }

    private fun resultSummary(
        label: String,
        results: EnrichmentResults,
        types: Set<EnrichmentType>,
    ) {
        val success = results.raw.count { it.value is EnrichmentResult.Success }
        val notFound = results.raw.count { it.value is EnrichmentResult.NotFound }
        val errors = results.raw.count { it.value is EnrichmentResult.Error }
        val rateLimited = results.raw.count { it.value is EnrichmentResult.RateLimited }
        log("    $label: $success success, $notFound notfound, $errors errors, $rateLimited ratelimited (of ${types.size} requested)")
        results.raw.forEach { (type, result) ->
            val status = when (result) {
                is EnrichmentResult.Success -> "OK  conf=%.2f provider=%-18s %s".format(
                    result.confidence, result.provider, snippet(result.data),
                )
                is EnrichmentResult.NotFound -> "NF  provider=${result.provider}"
                is EnrichmentResult.Error -> "ERR (${result.errorKind}) ${result.message.take(60)}"
                is EnrichmentResult.RateLimited -> "RL  provider=${result.provider}"
            }
            log("      %-22s %s".format(type.name, status))
        }
    }

    private fun snippet(data: EnrichmentData): String = when (data) {
        is EnrichmentData.Artwork -> "${data.sizes?.size ?: 0} sizes, url=${data.url.take(50)}"
        is EnrichmentData.Metadata -> buildString {
            data.genreTags?.let { append("tags=${it.take(2).joinToString { t -> "${t.name}(${t.confidence})" }}") }
                ?: data.genres?.let { append("genres=${it.take(3)}") }
            data.label?.let { append(" label=$it") }
            data.trackCount?.let { append(" tracks=$it") }
            data.communityRating?.let { append(" rating=$it") }
        }
        is EnrichmentData.Lyrics -> "synced=${data.syncedLyrics != null} plain=${data.plainLyrics != null} instrumental=${data.isInstrumental}"
        is EnrichmentData.Biography -> "${data.text.take(60)}..."
        is EnrichmentData.SimilarArtists -> "${data.artists.size} artists: ${data.artists.take(2).joinToString { it.name }}"
        is EnrichmentData.Popularity -> "listeners=${data.listenerCount} plays=${data.listenCount}"
        is EnrichmentData.BandMembers -> "${data.members.size} members"
        is EnrichmentData.Discography -> "${data.albums.size} albums"
        is EnrichmentData.Tracklist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarTracks -> "${data.tracks.size} tracks"
        is EnrichmentData.ArtistLinks -> "${data.links.size} links"
        is EnrichmentData.Credits -> {
            val cats = data.credits.groupBy { it.roleCategory ?: "other" }
            "${data.credits.size} credits (${cats.entries.joinToString { "${it.value.size} ${it.key}" }})"
        }
        is EnrichmentData.ReleaseEditions -> "${data.editions.size} editions"
        is EnrichmentData.ArtistTimeline -> "${data.events.size} events"
        is EnrichmentData.RadioPlaylist -> "${data.tracks.size} tracks"
        is EnrichmentData.SimilarAlbums -> "${data.albums.size} similar albums"
        is EnrichmentData.GenreDiscovery -> "${data.relatedGenres.size} related genres"
        is EnrichmentData.TopTracks -> "${data.tracks.size} top tracks"
        is EnrichmentData.TrackPreview -> "url=${data.url.take(50)} duration=${data.durationMs}ms source=${data.source}"
    }

    // =================================================================
    // 1. SPECIAL CHARACTERS AND ENCODING
    // =================================================================

    @Test
    fun `01 - special characters in names`() = runBlocking {
        banner("SPECIAL CHARACTERS")
        val cases = listOf(
            Triple("AC/DC", "Back in Black", "slash in artist"),
            Triple("Björk", "Post", "diacritical marks"),
            Triple("Sigur Rós", "( )", "accented + parens in album"),
            Triple("Guns N' Roses", "Appetite for Destruction", "apostrophe"),
            Triple("The Notorious B.I.G.", "Ready to Die", "periods in name"),
            Triple("Beyoncé", "Lemonade", "accented e"),
            Triple("Mötley Crüe", "Dr. Feelgood", "umlauts"),
            Triple("Sunn O)))", "Monoliths & Dimensions", "parens + ampersand"),
        )
        for ((artist, album, note) in cases) {
            log("    --- $artist / $album ($note) ---")
            val results = engine.enrich(
                EnrichmentRequest.forAlbum(album, artist),
                setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_METADATA),
            )
            val found = results.raw.count { it.value is EnrichmentResult.Success }
            val types = results.raw.entries.joinToString(", ") { (t, r) ->
                val s = if (r is EnrichmentResult.Success) "OK" else "NF"
                "${t.name}=$s"
            }
            log("      $found/3 found: $types")
        }
    }

    // =================================================================
    // 2. NON-LATIN SCRIPTS
    // =================================================================

    @Test
    fun `02 - non-latin scripts`() = runBlocking {
        banner("NON-LATIN SCRIPTS")
        val cases = listOf(
            Triple("坂本龍一", "async", "Japanese (Ryuichi Sakamoto)"),
            Triple("Рахманинов", "Piano Concerto No. 2", "Cyrillic (Rachmaninoff)"),
            Triple("이소라", "Track 10", "Korean (Lee Sora)"),
            Triple("周杰倫", "范特西", "Chinese (Jay Chou / Fantasy)"),
            Triple("אביב גפן", "Winter", "Hebrew (Aviv Geffen)"),
        )
        for ((artist, album, note) in cases) {
            log("    --- $artist / $album ($note) ---")
            val results = engine.enrich(
                EnrichmentRequest.forAlbum(album, artist),
                setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_ART),
            )
            val found = results.raw.count { it.value is EnrichmentResult.Success }
            log("      $found/2 found: ${results.raw.entries.joinToString { "${it.key.name}=${if (it.value is EnrichmentResult.Success) "OK" else "NF"}" }}")
        }
    }

    // =================================================================
    // 3. NONEXISTENT / GARBAGE INPUT
    // =================================================================

    @Test
    fun `03 - nonexistent and garbage input`() = runBlocking {
        banner("NONEXISTENT / GARBAGE INPUT")
        val cases = listOf(
            Triple("xyznonexistent12345", "fakealbumabc", "completely fake"),
            Triple("", "", "empty strings"),
            Triple("a", "b", "single characters"),
            Triple("The Beatles" + "x".repeat(200), "Abbey Road", "very long artist name"),
            Triple("Radiohead", "x".repeat(500), "very long album name"),
            Triple("'; DROP TABLE artists;--", "sql injection", "SQL injection attempt"),
            Triple("<script>alert(1)</script>", "xss", "XSS attempt"),
        )
        for ((artist, album, note) in cases) {
            log("    --- ($note) ---")
            try {
                val results = engine.enrich(
                    EnrichmentRequest.forAlbum(album.take(200), artist.take(200)),
                    setOf(EnrichmentType.GENRE),
                )
                val result = results.raw[EnrichmentType.GENRE]
                val status = when (result) {
                    is EnrichmentResult.Success -> "SUCCESS (unexpected!)"
                    is EnrichmentResult.NotFound -> "NotFound (expected)"
                    is EnrichmentResult.Error -> "Error(${result.errorKind}): ${result.message.take(50)}"
                    is EnrichmentResult.RateLimited -> "RateLimited"
                    null -> "null (no provider)"
                }
                log("      $status")
            } catch (e: Exception) {
                log("      EXCEPTION: ${e::class.simpleName}: ${e.message?.take(80)}")
            }
        }
    }

    // =================================================================
    // 4. COMPOSITE TYPE EDGE CASES (ARTIST_TIMELINE)
    // =================================================================

    @Test
    fun `04 - composite type edges`() = runBlocking {
        banner("COMPOSITE TYPE EDGES (ARTIST_TIMELINE)")

        // Solo artist (no band members)
        log("    --- Solo artist: Ed Sheeran ---")
        var results = engine.enrich(
            EnrichmentRequest.forArtist("Ed Sheeran"),
            setOf(EnrichmentType.ARTIST_TIMELINE),
        )
        logTimelineResult(results)

        // Classical composer (long timeline)
        log("    --- Classical: Ludwig van Beethoven ---")
        results = engine.enrich(
            EnrichmentRequest.forArtist("Ludwig van Beethoven"),
            setOf(EnrichmentType.ARTIST_TIMELINE),
        )
        logTimelineResult(results)

        // Active supergroup with many member changes
        log("    --- Many members: Foo Fighters ---")
        results = engine.enrich(
            EnrichmentRequest.forArtist("Foo Fighters"),
            setOf(EnrichmentType.ARTIST_TIMELINE),
        )
        logTimelineResult(results)

        // Brand new artist (minimal data)
        log("    --- New artist: Chappell Roan ---")
        results = engine.enrich(
            EnrichmentRequest.forArtist("Chappell Roan"),
            setOf(EnrichmentType.ARTIST_TIMELINE),
        )
        logTimelineResult(results)

        // Request ARTIST_TIMELINE + its sub-types explicitly
        log("    --- Explicit sub-types + composite: Radiohead ---")
        results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(
                EnrichmentType.ARTIST_TIMELINE,
                EnrichmentType.ARTIST_DISCOGRAPHY,
                EnrichmentType.BAND_MEMBERS,
            ),
        )
        val timeline = results.raw[EnrichmentType.ARTIST_TIMELINE]
        val disco = results.raw[EnrichmentType.ARTIST_DISCOGRAPHY]
        val members = results.raw[EnrichmentType.BAND_MEMBERS]
        log("      TIMELINE: ${if (timeline is EnrichmentResult.Success) "${(timeline.data as EnrichmentData.ArtistTimeline).events.size} events" else timeline?.let { it::class.simpleName } ?: "null"}")
        log("      DISCOGRAPHY: ${if (disco is EnrichmentResult.Success) "${(disco.data as EnrichmentData.Discography).albums.size} albums" else disco?.let { it::class.simpleName } ?: "null"}")
        log("      BAND_MEMBERS: ${if (members is EnrichmentResult.Success) "${(members.data as EnrichmentData.BandMembers).members.size} members" else members?.let { it::class.simpleName } ?: "null"}")
    }

    private fun logTimelineResult(results: EnrichmentResults) {
        val tl = results.raw[EnrichmentType.ARTIST_TIMELINE]
        if (tl is EnrichmentResult.Success) {
            val data = tl.data as EnrichmentData.ArtistTimeline
            val byType = data.events.groupBy { it.type }
            log("      ${data.events.size} events: ${byType.entries.joinToString { "${it.value.size} ${it.key}" }}")
            data.events.take(3).forEach { log("        ${it.date.padEnd(12)} ${it.type.padEnd(18)} ${it.description.take(50)}") }
            if (data.events.size > 3) log("        ... +${data.events.size - 3} more")
        } else {
            log("      ${tl?.let { it::class.simpleName } ?: "null"}")
        }
    }

    // =================================================================
    // 5. CREDITS EDGE CASES
    // =================================================================

    @Test
    fun `05 - credits edge cases`() = runBlocking {
        banner("CREDITS EDGE CASES")

        // Instrumental track (no vocals credit)
        log("    --- Instrumental: YYZ by Rush ---")
        var results = engine.enrich(
            EnrichmentRequest.forTrack("YYZ", "Rush", album = "Moving Pictures"),
            setOf(EnrichmentType.CREDITS),
        )
        logCreditsResult(results)

        // Classical with many performers
        log("    --- Classical: Symphony No. 9 by Beethoven ---")
        results = engine.enrich(
            EnrichmentRequest.forTrack("Symphony No. 9", "Ludwig van Beethoven"),
            setOf(EnrichmentType.CREDITS),
        )
        logCreditsResult(results)

        // Electronic (likely producer-heavy)
        log("    --- Electronic: Around the World by Daft Punk ---")
        results = engine.enrich(
            EnrichmentRequest.forTrack("Around the World", "Daft Punk", album = "Homework"),
            setOf(EnrichmentType.CREDITS),
        )
        logCreditsResult(results)

        // Very recent track
        log("    --- Recent: APT. by ROSÉ & Bruno Mars ---")
        results = engine.enrich(
            EnrichmentRequest.forTrack("APT.", "ROSÉ", album = "rosie"),
            setOf(EnrichmentType.CREDITS),
        )
        logCreditsResult(results)
    }

    private fun logCreditsResult(results: EnrichmentResults) {
        val cr = results.raw[EnrichmentType.CREDITS]
        if (cr is EnrichmentResult.Success) {
            val data = cr.data as EnrichmentData.Credits
            val cats = data.credits.groupBy { it.roleCategory ?: "other" }
            log("      provider=${cr.provider} conf=${cr.confidence} total=${data.credits.size}")
            cats.forEach { (cat, credits) ->
                log("      [$cat] ${credits.joinToString(", ") { "${it.name}(${it.role})" }}")
            }
        } else {
            val status = when (cr) {
                is EnrichmentResult.NotFound -> "NotFound(${cr.provider})"
                is EnrichmentResult.Error -> "Error(${cr.errorKind}): ${cr.message.take(60)}"
                else -> cr?.let { it::class.simpleName } ?: "null"
            }
            log("      $status")
        }
    }

    // =================================================================
    // 6. GENRE MERGE BEHAVIOR
    // =================================================================

    @Test
    fun `06 - genre merge behavior`() = runBlocking {
        banner("GENRE MERGE BEHAVIOR")

        val artists = listOf(
            "Radiohead" to "well-known rock",
            "Kendrick Lamar" to "hip-hop (confirmed merge)",
            "Miles Davis" to "jazz legend",
            "Billie Eilish" to "recent pop",
            "Amon Amarth" to "melodic death metal (niche genre)",
            "Boards of Canada" to "IDM/ambient (obscure subgenres)",
        )
        for ((artist, note) in artists) {
            log("    --- $artist ($note) ---")
            val results = engine.enrich(
                EnrichmentRequest.forArtist(artist),
                setOf(EnrichmentType.GENRE),
            )
            val genre = results.raw[EnrichmentType.GENRE]
            if (genre is EnrichmentResult.Success) {
                val data = genre.data as EnrichmentData.Metadata
                log("      provider=${genre.provider} merged=${genre.provider == "genre_merger"}")
                data.genreTags?.take(5)?.forEach { tag ->
                    log("        %-24s conf=%.2f sources=%s".format(tag.name, tag.confidence, tag.sources))
                } ?: log("      genres: ${data.genres?.take(5)}")
            } else {
                log("      ${genre?.let { it::class.simpleName } ?: "null"}")
            }
        }
    }

    // =================================================================
    // 7. RELEASE EDITIONS EDGE CASES
    // =================================================================

    @Test
    fun `07 - release editions edge cases`() = runBlocking {
        banner("RELEASE EDITIONS EDGE CASES")

        val albums = listOf(
            Triple("Dark Side of the Moon", "Pink Floyd", "massive reissue history"),
            Triple("Thriller", "Michael Jackson", "best-selling album"),
            Triple("untitled unmastered.", "Kendrick Lamar", "unusual album title"),
            Triple("LP1", "FKA twigs", "short ambiguous title"),
            Triple("I", "Meshuggah", "single-character album name"),
        )
        for ((album, artist, note) in albums) {
            log("    --- $album by $artist ($note) ---")
            val results = engine.enrich(
                EnrichmentRequest.forAlbum(album, artist),
                setOf(EnrichmentType.RELEASE_EDITIONS),
            )
            val ed = results.raw[EnrichmentType.RELEASE_EDITIONS]
            if (ed is EnrichmentResult.Success) {
                val data = ed.data as EnrichmentData.ReleaseEditions
                val countries = data.editions.mapNotNull { it.country }.distinct()
                val years = data.editions.mapNotNull { it.year }.distinct().sorted()
                log("      ${data.editions.size} editions, ${countries.size} countries, years=${years.take(4)}")
                data.editions.take(3).forEach { e ->
                    log("        ${e.title.take(35).padEnd(35)} ${(e.country ?: "-").padEnd(4)} ${e.year ?: "-"}")
                }
            } else {
                log("      ${ed?.let { when (it) {
                    is EnrichmentResult.NotFound -> "NotFound(${it.provider})"
                    is EnrichmentResult.Error -> "Error(${it.errorKind})"
                    else -> it::class.simpleName
                }} ?: "null"}")
            }
        }
    }

    // =================================================================
    // 8. PROVIDER FALLBACK CHAIN BEHAVIOR
    // =================================================================

    @Test
    fun `08 - provider fallback chains`() = runBlocking {
        banner("PROVIDER FALLBACK BEHAVIOR")

        // Album with strong MusicBrainz match — should get primary providers
        log("    --- High-confidence: Abbey Road by The Beatles ---")
        val allTypes = EnrichmentType.entries.toSet()
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("Abbey Road", "The Beatles"),
            allTypes,
        )
        resultSummary("Abbey Road (all types)", results, allTypes)

        // Obscure album — tests fallback behavior
        log("\n    --- Obscure: Cosmogramma by Flying Lotus ---")
        val results2 = engine.enrich(
            EnrichmentRequest.forAlbum("Cosmogramma", "Flying Lotus"),
            setOf(
                EnrichmentType.ALBUM_ART, EnrichmentType.GENRE,
                EnrichmentType.ALBUM_METADATA, EnrichmentType.ALBUM_TRACKS,
                EnrichmentType.CREDITS, EnrichmentType.RELEASE_EDITIONS,
            ),
        )
        resultSummary("Cosmogramma", results2, results2.raw.keys)
    }

    // =================================================================
    // 9. CACHE BEHAVIOR
    // =================================================================

    @Test
    fun `09 - cache behavior`() = runBlocking {
        banner("CACHE BEHAVIOR (same request twice)")

        val request = EnrichmentRequest.forArtist("Daft Punk")
        val types = setOf(EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO, EnrichmentType.SIMILAR_ARTISTS)

        val start1 = System.currentTimeMillis()
        val results1 = engine.enrich(request, types)
        val time1 = System.currentTimeMillis() - start1

        val start2 = System.currentTimeMillis()
        val results2 = engine.enrich(request, types)
        val time2 = System.currentTimeMillis() - start2

        log("    First call:  ${time1}ms (${results1.raw.count { it.value is EnrichmentResult.Success }} success)")
        log("    Second call: ${time2}ms (${results2.raw.count { it.value is EnrichmentResult.Success }} success)")
        log("    Speedup: ${if (time2 > 0) "${time1 / time2}x" else "instant"}")
        log("    Same results: ${results1.raw.keys == results2.raw.keys}")
    }

    // =================================================================
    // 10. SIMULTANEOUS MULTI-TYPE REQUESTS
    // =================================================================

    @Test
    fun `10 - all types for all request kinds`() = runBlocking {
        banner("ALL TYPES BY REQUEST KIND")
        val allTypes = EnrichmentType.entries.toSet()

        log("    --- ForArtist: Radiohead ---")
        val artistR = engine.enrich(EnrichmentRequest.forArtist("Radiohead"), allTypes)
        val artistSuccess = artistR.raw.count { it.value is EnrichmentResult.Success }
        log("      $artistSuccess/${allTypes.size} types returned data")
        log("      Successful: ${artistR.raw.filter { it.value is EnrichmentResult.Success }.keys.joinToString { it.name }}")

        log("    --- ForAlbum: OK Computer by Radiohead ---")
        val albumR = engine.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), allTypes)
        val albumSuccess = albumR.raw.count { it.value is EnrichmentResult.Success }
        log("      $albumSuccess/${allTypes.size} types returned data")
        log("      Successful: ${albumR.raw.filter { it.value is EnrichmentResult.Success }.keys.joinToString { it.name }}")

        log("    --- ForTrack: Paranoid Android by Radiohead ---")
        val trackR = engine.enrich(
            EnrichmentRequest.forTrack("Paranoid Android", "Radiohead", album = "OK Computer"),
            allTypes,
        )
        val trackSuccess = trackR.raw.count { it.value is EnrichmentResult.Success }
        log("      $trackSuccess/${allTypes.size} types returned data")
        log("      Successful: ${trackR.raw.filter { it.value is EnrichmentResult.Success }.keys.joinToString { it.name }}")
    }

    // =================================================================
    // 11. FINAL SUMMARY
    // =================================================================

    @Test
    fun `11 - analysis summary`() {
        banner("ANALYSIS COMPLETE")
        log("    Full output above. Review for:")
        log("      - Types that consistently return NotFound")
        log("      - Error patterns (ErrorKind distribution)")
        log("      - Genre merge activation (genre_merger vs single provider)")
        log("      - Composite type robustness (partial timelines)")
        log("      - Special character handling failures")
        log("      - Cache speedup factor")
    }
}
