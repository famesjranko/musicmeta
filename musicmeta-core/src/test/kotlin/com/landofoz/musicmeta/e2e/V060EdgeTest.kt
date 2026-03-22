package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SimilarArtist
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
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class V060EdgeTest {
    companion object {
        private lateinit var engine: EnrichmentEngine

        private fun prop(name: String) = System.getProperty(name, System.getenv(name.replace(".", "_").uppercase()) ?: "")

        @BeforeClass
        @JvmStatic
        fun setup() {
            Assume.assumeTrue("E2E tests disabled", System.getProperty("include.e2e") == "true")
            val http = DefaultHttpClient("musicmeta-edge-test/0.6.0 (https://github.com/famesjranko/musicmeta)")
            val deezerRl = RateLimiter(100)
            val deezerApi = DeezerApi(http, deezerRl)
            engine = EnrichmentEngine.Builder()
                .addProvider(MusicBrainzProvider(http, RateLimiter(1100)))
                .addProvider(CoverArtArchiveProvider(http, RateLimiter(100)))
                .addProvider(WikidataProvider(http, RateLimiter(100)))
                .addProvider(WikipediaProvider(http, RateLimiter(100)))
                .addProvider(LrcLibProvider(http, RateLimiter(200)))
                .addProvider(DeezerProvider(http, deezerRl))
                .addProvider(SimilarAlbumsProvider(deezerApi))
                .addProvider(ITunesProvider(http, RateLimiter(3000)))
                .addProvider(ListenBrainzProvider(http, RateLimiter(100)))
                .addProvider(LastFmProvider(prop("lastfm.apikey"), http, RateLimiter(200)))
                .addProvider(FanartTvProvider(prop("fanarttv.apikey"), http, RateLimiter(100)))
                .addProvider(DiscogsProvider(prop("discogs.token"), http, RateLimiter(100)))
                .build()
        }
    }

    // ═══════════════════════════════════════════════════════
    // SIMILAR_ARTISTS MERGE EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `01 - similar artists merge - well-known artist`() = runBlocking {
        println("\n" + "=".repeat(64))
        println("  SIMILAR_ARTISTS MERGE EDGES")
        println("=".repeat(64))

        println("  --- Radiohead (well-known, expect multi-source) ---")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarArtists
            val artists = data.artists
            val multiSource = artists.filter { it.sources.size > 1 }
            println("    provider: ${sa.provider}, conf=${sa.confidence}")
            println("    total: ${artists.size} artists")
            println("    multi-source: ${multiSource.size}/${artists.size}")
            println("    score range: ${artists.minOfOrNull { it.matchScore }}..${artists.maxOfOrNull { it.matchScore }}")
            println("    capped at 1.0: ${artists.count { it.matchScore >= 1.0f }}")
            println("    sources distribution:")
            val bySource = artists.flatMap { it.sources }.groupingBy { it }.eachCount()
            bySource.forEach { (src, count) -> println("      $src: $count artists") }
            println("    top 5:")
            artists.take(5).forEach { println("      ${it.name.padEnd(30)} score=${it.matchScore} sources=${it.sources} mbid=${it.identifiers.musicBrainzId ?: "none"}") }
            val names = artists.map { it.name.lowercase() }
            val dupes = names.groupingBy { it }.eachCount().filter { it.value > 1 }
            if (dupes.isNotEmpty()) println("    BUG: duplicate names: $dupes")
            else println("    dedup: OK (no duplicates)")
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider}")
        }
    }

    @Test
    fun `02 - similar artists merge - obscure artist`() = runBlocking {
        println("\n  --- Boards of Canada (niche, may be single-source) ---")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Boards of Canada"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarArtists
            val artists = data.artists
            val multiSource = artists.filter { it.sources.size > 1 }
            println("    total: ${artists.size}, multi-source: ${multiSource.size}")
            println("    sources: ${artists.flatMap { it.sources }.distinct()}")
            artists.take(3).forEach { println("      ${it.name.padEnd(30)} score=${it.matchScore} sources=${it.sources}") }
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }}")
        }
    }

    @Test
    fun `03 - similar artists merge - non-latin artist`() = runBlocking {
        println("\n  --- 坂本龍一 (Ryuichi Sakamoto — Japanese characters) ---")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("坂本龍一"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarArtists
            println("    total: ${data.artists.size}, sources: ${data.artists.flatMap { it.sources }.distinct()}")
            data.artists.take(3).forEach { println("      ${it.name.padEnd(30)} score=${it.matchScore}") }
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider}")
        }
    }

    @Test
    fun `04 - similar artists - wrong request type`() = runBlocking {
        println("\n  --- ForAlbum: OK Computer (wrong request type for SIMILAR_ARTISTS) ---")
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider ?: (sa as? EnrichmentResult.Success)?.provider}")
    }

    // ═══════════════════════════════════════════════════════
    // ARTIST_RADIO EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `05 - artist radio edges`() = runBlocking {
        println("\n" + "=".repeat(64))
        println("  ARTIST_RADIO EDGES")
        println("=".repeat(64))

        println("  --- Radiohead (well-known) ---")
        var results = engine.enrich(EnrichmentRequest.forArtist("Radiohead"), setOf(EnrichmentType.ARTIST_RADIO))
        var radio = results[EnrichmentType.ARTIST_RADIO]
        if (radio is EnrichmentResult.Success) {
            val data = radio.data as EnrichmentData.RadioPlaylist
            println("    tracks: ${data.tracks.size}")
            println("    has durations: ${data.tracks.count { it.durationMs != null }}/${data.tracks.size}")
            println("    has albums: ${data.tracks.count { it.album != null }}/${data.tracks.size}")
            println("    has identifiers: ${data.tracks.count { it.identifiers.extra.containsKey("deezerId") }}/${data.tracks.size}")
            val uniqueArtists = data.tracks.map { it.artist }.distinct()
            println("    unique artists: ${uniqueArtists.size}")
            println("    seed artist tracks: ${data.tracks.count { it.artist.equals("Radiohead", ignoreCase = true) }}")
            data.tracks.take(5).forEach { println("      ${it.title.take(35).padEnd(35)} ${it.artist.padEnd(20)} ${it.durationMs?.let { d -> "${d/1000}s" } ?: "?"}") }
            val dupes = data.tracks.groupBy { "${it.title}:${it.artist}".lowercase() }.filter { it.value.size > 1 }
            if (dupes.isNotEmpty()) println("    BUG: duplicate tracks: ${dupes.keys}")
            else println("    dedup: OK")
        } else {
            println("    RESULT: ${radio?.let { it::class.simpleName }}")
        }

        println("\n  --- The Gerogerigegege (very obscure) ---")
        results = engine.enrich(EnrichmentRequest.forArtist("The Gerogerigegege"), setOf(EnrichmentType.ARTIST_RADIO))
        radio = results[EnrichmentType.ARTIST_RADIO]
        println("    RESULT: ${radio?.let { it::class.simpleName }} — ${(radio as? EnrichmentResult.NotFound)?.provider ?: "found ${((radio as? EnrichmentResult.Success)?.data as? EnrichmentData.RadioPlaylist)?.tracks?.size} tracks"}")

        println("\n  --- ForAlbum: OK Computer (wrong request type) ---")
        results = engine.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), setOf(EnrichmentType.ARTIST_RADIO))
        radio = results[EnrichmentType.ARTIST_RADIO]
        println("    RESULT: ${radio?.let { it::class.simpleName }} — ${(radio as? EnrichmentResult.NotFound)?.provider ?: "unexpected success!"}")

        println("\n  --- ForTrack: Creep (wrong request type) ---")
        results = engine.enrich(EnrichmentRequest.forTrack("Creep", "Radiohead"), setOf(EnrichmentType.ARTIST_RADIO))
        radio = results[EnrichmentType.ARTIST_RADIO]
        println("    RESULT: ${radio?.let { it::class.simpleName }} — ${(radio as? EnrichmentResult.NotFound)?.provider ?: "unexpected success!"}")

        println("\n  --- AC/DC (special characters) ---")
        results = engine.enrich(EnrichmentRequest.forArtist("AC/DC"), setOf(EnrichmentType.ARTIST_RADIO))
        radio = results[EnrichmentType.ARTIST_RADIO]
        if (radio is EnrichmentResult.Success) {
            val data = radio.data as EnrichmentData.RadioPlaylist
            println("    tracks: ${data.tracks.size} (special chars handled OK)")
        } else {
            println("    RESULT: ${radio?.let { it::class.simpleName }} — ${(radio as? EnrichmentResult.NotFound)?.provider}")
        }
    }

    // ═══════════════════════════════════════════════════════
    // SIMILAR_ALBUMS EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `06 - similar albums edges`() = runBlocking {
        println("\n" + "=".repeat(64))
        println("  SIMILAR_ALBUMS EDGES")
        println("=".repeat(64))

        println("  --- OK Computer by Radiohead (well-known, 1997) ---")
        var results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        var sa = results[EnrichmentType.SIMILAR_ALBUMS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarAlbums
            println("    total: ${data.albums.size}")
            println("    score range: ${data.albums.minOfOrNull { it.artistMatchScore }}..${data.albums.maxOfOrNull { it.artistMatchScore }}")
            println("    with year: ${data.albums.count { it.year != null }}/${data.albums.size}")
            println("    with thumbnail: ${data.albums.count { it.thumbnailUrl != null }}/${data.albums.size}")
            println("    unique artists: ${data.albums.map { it.artist }.distinct().size}")
            val selfAlbums = data.albums.filter { it.artist.equals("Radiohead", ignoreCase = true) }
            if (selfAlbums.isNotEmpty()) println("    BUG: contains seed artist's own albums: ${selfAlbums.map { it.title }}")
            else println("    self-filter: OK (no seed artist albums)")
            val dupes = data.albums.groupBy { "${it.title}:${it.artist}".lowercase() }.filter { it.value.size > 1 }
            if (dupes.isNotEmpty()) println("    BUG: duplicate albums: ${dupes.keys}")
            else println("    dedup: OK")
            data.albums.take(5).forEach { println("      ${it.title.take(35).padEnd(35)} ${it.artist.padEnd(20)} ${it.year ?: "?"} score=${it.artistMatchScore}") }
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }}")
        }

        println("\n  --- DAMN. by Kendrick Lamar (hip-hop, 2017) ---")
        results = engine.enrich(
            EnrichmentRequest.forAlbum("DAMN.", "Kendrick Lamar"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        sa = results[EnrichmentType.SIMILAR_ALBUMS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarAlbums
            println("    total: ${data.albums.size}, unique artists: ${data.albums.map { it.artist }.distinct().size}")
            data.albums.take(3).forEach { println("      ${it.title.take(35).padEnd(35)} ${it.artist.padEnd(20)} ${it.year ?: "?"} score=${it.artistMatchScore}") }
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider}")
        }

        println("\n  --- ForArtist: Radiohead (wrong request type) ---")
        results = engine.enrich(EnrichmentRequest.forArtist("Radiohead"), setOf(EnrichmentType.SIMILAR_ALBUMS))
        sa = results[EnrichmentType.SIMILAR_ALBUMS]
        println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider ?: "unexpected success!"}")

        println("\n  --- Cosmogramma by Flying Lotus (niche) ---")
        results = engine.enrich(
            EnrichmentRequest.forAlbum("Cosmogramma", "Flying Lotus"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        sa = results[EnrichmentType.SIMILAR_ALBUMS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarAlbums
            println("    total: ${data.albums.size}")
            data.albums.take(3).forEach { println("      ${it.title.take(35).padEnd(35)} ${it.artist.padEnd(20)} score=${it.artistMatchScore}") }
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider}")
        }

        println("\n  --- I by Meshuggah (single char album) ---")
        results = engine.enrich(
            EnrichmentRequest.forAlbum("I", "Meshuggah"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        sa = results[EnrichmentType.SIMILAR_ALBUMS]
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarAlbums
            println("    total: ${data.albums.size}")
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }} — ${(sa as? EnrichmentResult.NotFound)?.provider}")
        }
    }

    // ═══════════════════════════════════════════════════════
    // GENRE_DISCOVERY EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `07 - genre discovery edges`() = runBlocking {
        println("\n" + "=".repeat(64))
        println("  GENRE_DISCOVERY EDGES")
        println("=".repeat(64))

        println("  --- Radiohead (rock/electronic — expect rich discovery) ---")
        var results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        var gd = results[EnrichmentType.GENRE_DISCOVERY]
        if (gd is EnrichmentResult.Success) {
            val data = gd.data as EnrichmentData.GenreDiscovery
            println("    total: ${data.relatedGenres.size}")
            val byRel = data.relatedGenres.groupBy { it.relationship }
            byRel.forEach { (rel, genres) -> println("    $rel: ${genres.size}") }
            println("    affinity range: ${data.relatedGenres.minOfOrNull { it.affinity }}..${data.relatedGenres.maxOfOrNull { it.affinity }}")
            data.relatedGenres.take(5).forEach { println("      ${it.name.padEnd(25)} aff=${it.affinity} rel=${it.relationship} from=${it.sourceGenres}") }
        } else {
            println("    RESULT: ${gd?.let { it::class.simpleName }}")
        }

        println("\n  --- Amon Amarth (melodic death metal — may not be in taxonomy) ---")
        results = engine.enrich(
            EnrichmentRequest.forArtist("Amon Amarth"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        gd = results[EnrichmentType.GENRE_DISCOVERY]
        if (gd is EnrichmentResult.Success) {
            val data = gd.data as EnrichmentData.GenreDiscovery
            println("    total: ${data.relatedGenres.size}")
            data.relatedGenres.take(5).forEach { println("      ${it.name.padEnd(25)} aff=${it.affinity} from=${it.sourceGenres}") }
        } else {
            println("    RESULT: ${gd?.let { it::class.simpleName }} — ${(gd as? EnrichmentResult.NotFound)?.provider}")
        }

        println("\n  --- ForAlbum: OK Computer (should work — albums have genres) ---")
        results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        gd = results[EnrichmentType.GENRE_DISCOVERY]
        if (gd is EnrichmentResult.Success) {
            val data = gd.data as EnrichmentData.GenreDiscovery
            println("    total: ${data.relatedGenres.size} related genres (album genre discovery works)")
        } else {
            println("    RESULT: ${gd?.let { it::class.simpleName }} — ${(gd as? EnrichmentResult.NotFound)?.provider}")
        }

        println("\n  --- ForTrack: Creep by Radiohead (track — may lack genreTags) ---")
        results = engine.enrich(
            EnrichmentRequest.forTrack("Creep", "Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        gd = results[EnrichmentType.GENRE_DISCOVERY]
        println("    RESULT: ${gd?.let { it::class.simpleName }} — ${(gd as? EnrichmentResult.NotFound)?.provider ?: "found ${((gd as? EnrichmentResult.Success)?.data as? EnrichmentData.GenreDiscovery)?.relatedGenres?.size} genres"}")
    }

    // ═══════════════════════════════════════════════════════
    // CONCURRENT ENRICHMENT (all v0.6.0 types at once)
    // ═══════════════════════════════════════════════════════

    @Test
    fun `08 - all v060 types concurrent`() = runBlocking {
        println("\n" + "=".repeat(64))
        println("  ALL v0.6.0 TYPES CONCURRENT")
        println("=".repeat(64))

        println("  --- ForArtist: Daft Punk (all rec types at once) ---")
        val start = System.currentTimeMillis()
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Daft Punk"),
            setOf(
                EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentType.ARTIST_RADIO,
                EnrichmentType.GENRE_DISCOVERY,
                EnrichmentType.GENRE,
                EnrichmentType.ARTIST_BIO,
            ),
        )
        val elapsed = System.currentTimeMillis() - start
        println("    elapsed: ${elapsed}ms")
        results.forEach { (type, result) ->
            val status = when (result) {
                is EnrichmentResult.Success -> "OK (${result.provider}, conf=${result.confidence})"
                is EnrichmentResult.NotFound -> "NF (${result.provider})"
                is EnrichmentResult.Error -> "ERR (${result.provider}: ${result.message})"
                is EnrichmentResult.RateLimited -> "RL (${result.provider})"
            }
            val detail = when (result) {
                is EnrichmentResult.Success -> when (val d = result.data) {
                    is EnrichmentData.SimilarArtists -> "${d.artists.size} artists"
                    is EnrichmentData.RadioPlaylist -> "${d.tracks.size} tracks"
                    is EnrichmentData.GenreDiscovery -> "${d.relatedGenres.size} genres"
                    is EnrichmentData.Metadata -> d.genreTags?.size?.let { "$it tags" } ?: "metadata"
                    is EnrichmentData.Biography -> "${d.text.take(50)}..."
                    else -> d::class.simpleName ?: ""
                }
                else -> ""
            }
            println("    ${type.name.padEnd(20)} $status $detail")
        }

        println("\n  --- ForAlbum: Random Access Memories (SIMILAR_ALBUMS + GENRE_DISCOVERY) ---")
        val start2 = System.currentTimeMillis()
        val results2 = engine.enrich(
            EnrichmentRequest.forAlbum("Random Access Memories", "Daft Punk"),
            setOf(
                EnrichmentType.SIMILAR_ALBUMS,
                EnrichmentType.GENRE_DISCOVERY,
                EnrichmentType.GENRE,
            ),
        )
        val elapsed2 = System.currentTimeMillis() - start2
        println("    elapsed: ${elapsed2}ms")
        results2.forEach { (type, result) ->
            val status = when (result) {
                is EnrichmentResult.Success -> "OK"
                is EnrichmentResult.NotFound -> "NF (${result.provider})"
                else -> result::class.simpleName ?: ""
            }
            println("    ${type.name.padEnd(20)} $status")
        }
    }

    // ═══════════════════════════════════════════════════════
    // SUMMARY
    // ═══════════════════════════════════════════════════════

    @Test
    fun `09 - analysis summary`() {
        println("\n" + "=".repeat(64))
        println("  v0.6.0 EDGE ANALYSIS COMPLETE")
        println("=".repeat(64))
        println("  Review output above for:")
        println("    - Multi-source merge activation (lastfm + listenbrainz + deezer)")
        println("    - Duplicate detection in merged results")
        println("    - Score capping behavior (should not exceed 1.0)")
        println("    - Wrong request type handling (ForAlbum for artist-scoped types)")
        println("    - Special character handling in Deezer searches")
        println("    - Genre taxonomy coverage for niche genres")
        println("    - Self-referencing in similar albums (seed artist's own albums)")
        println("    - Concurrent request performance")
    }
}
