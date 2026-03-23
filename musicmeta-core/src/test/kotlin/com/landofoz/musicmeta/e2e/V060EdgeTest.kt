package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class V060EdgeTest {
    companion object {
        private lateinit var engine: EnrichmentEngine

        @BeforeClass
        @JvmStatic
        fun setup() {
            Assume.assumeTrue("E2E tests disabled", System.getProperty("include.e2e") == "true")
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
    }

    // ═══════════════════════════════════════════════════════
    // SIMILAR_ARTISTS MERGE EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `01 - similar artists merge - well-known artist returns results with valid scores`() = runBlocking {
        println("\n  --- Radiohead (well-known, expect multi-source) ---")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        assertTrue("Expected Success for Radiohead similar artists", sa is EnrichmentResult.Success)
        val data = (sa as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        val artists = data.artists

        // Structural invariants
        assertTrue("Should return at least 1 similar artist", artists.isNotEmpty())
        assertTrue("Merger should be the provider", sa.provider == "similar_artist_merger")
        artists.forEach { artist ->
            assertTrue("Score ${artist.matchScore} should be in [0, 1.0]", artist.matchScore in 0f..1.0f)
            assertTrue("Artist name should not be blank", artist.name.isNotBlank())
            assertTrue("Sources should not be empty for ${artist.name}", artist.sources.isNotEmpty())
        }

        // No duplicates by normalized name
        val names = artists.map { it.name.lowercase().trim() }
        val dupes = names.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("Duplicate artists found: $dupes", dupes.isEmpty())

        // Diagnostic output (for manual review of multi-source merge activation)
        val multiSource = artists.filter { it.sources.size > 1 }
        val bySource = artists.flatMap { it.sources }.groupingBy { it }.eachCount()
        println("    provider: ${sa.provider}, conf=${sa.confidence}")
        println("    total: ${artists.size}, multi-source: ${multiSource.size}")
        println("    sources: $bySource")
        println("    score range: ${artists.minOf { it.matchScore }}..${artists.maxOf { it.matchScore }}")
        artists.take(5).forEach { println("      ${it.name.padEnd(30)} score=${it.matchScore} sources=${it.sources}") }
        if (multiSource.isEmpty()) {
            println("    WARNING: no multi-source artists — check if Last.fm/ListenBrainz are responding")
        }
    }

    @Test
    fun `02 - similar artists merge - obscure artist`() = runBlocking {
        println("\n  --- Boards of Canada (niche) ---")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Boards of Canada"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        // Obscure artists may legitimately return NotFound — only assert invariants on Success
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarArtists
            data.artists.forEach { artist ->
                assertTrue("Score out of range for ${artist.name}", artist.matchScore in 0f..1.0f)
            }
            val names = data.artists.map { it.name.lowercase().trim() }
            assertTrue("Duplicates found", names.size == names.toSet().size)
            println("    total: ${data.artists.size}, sources: ${data.artists.flatMap { it.sources }.distinct()}")
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
        // Non-latin should not crash — any result type is acceptable
        assertNotNull("Result should not be null", sa)
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarArtists
            data.artists.forEach { artist ->
                assertTrue("Score out of range for ${artist.name}", artist.matchScore in 0f..1.0f)
            }
            println("    total: ${data.artists.size}, sources: ${data.artists.flatMap { it.sources }.distinct()}")
        } else {
            println("    RESULT: ${sa?.let { it::class.simpleName }}")
        }
    }

    @Test
    fun `04 - similar artists - wrong request type returns NotFound`() = runBlocking {
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.SIMILAR_ARTISTS),
        )
        val sa = results[EnrichmentType.SIMILAR_ARTISTS]
        assertTrue(
            "ForAlbum should return NotFound for SIMILAR_ARTISTS but got ${sa?.let { it::class.simpleName }}",
            sa is EnrichmentResult.NotFound,
        )
    }

    // ═══════════════════════════════════════════════════════
    // ARTIST_RADIO EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `05 - artist radio - well-known artist returns tracks with metadata`() = runBlocking {
        println("\n  --- Radiohead ARTIST_RADIO ---")
        val results = engine.enrich(EnrichmentRequest.forArtist("Radiohead"), setOf(EnrichmentType.ARTIST_RADIO))
        val radio = results[EnrichmentType.ARTIST_RADIO]
        assertTrue("Expected Success for Radiohead radio", radio is EnrichmentResult.Success)
        val data = (radio as EnrichmentResult.Success).data as EnrichmentData.RadioPlaylist

        // Structural invariants
        assertTrue("Should return at least 1 track", data.tracks.isNotEmpty())
        data.tracks.forEach { track ->
            assertTrue("Track title should not be blank", track.title.isNotBlank())
            assertTrue("Track artist should not be blank", track.artist.isNotBlank())
        }

        // No duplicate tracks
        val trackKeys = data.tracks.map { "${it.title}:${it.artist}".lowercase() }
        val dupes = trackKeys.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue("Duplicate tracks: ${dupes.keys}", dupes.isEmpty())

        // Should have variety (not all from the seed artist)
        val uniqueArtists = data.tracks.map { it.artist }.distinct()
        assertTrue("Expected multiple artists in radio, got: $uniqueArtists", uniqueArtists.size > 1)

        println("    tracks: ${data.tracks.size}, unique artists: ${uniqueArtists.size}")
        println("    has durations: ${data.tracks.count { it.durationMs != null }}/${data.tracks.size}")
    }

    @Test
    fun `05b - artist radio - wrong request types return NotFound`() = runBlocking {
        // ForAlbum
        val albumResults = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.ARTIST_RADIO),
        )
        assertTrue(
            "ForAlbum should return NotFound for ARTIST_RADIO",
            albumResults[EnrichmentType.ARTIST_RADIO] is EnrichmentResult.NotFound,
        )

        // ForTrack
        val trackResults = engine.enrich(
            EnrichmentRequest.forTrack("Creep", "Radiohead"),
            setOf(EnrichmentType.ARTIST_RADIO),
        )
        assertTrue(
            "ForTrack should return NotFound for ARTIST_RADIO",
            trackResults[EnrichmentType.ARTIST_RADIO] is EnrichmentResult.NotFound,
        )
    }

    @Test
    fun `05c - artist radio - special characters handled`() = runBlocking {
        val results = engine.enrich(EnrichmentRequest.forArtist("AC/DC"), setOf(EnrichmentType.ARTIST_RADIO))
        val radio = results[EnrichmentType.ARTIST_RADIO]
        // AC/DC is well-known enough that it should succeed; slash in name should not crash
        if (radio is EnrichmentResult.Success) {
            val data = radio.data as EnrichmentData.RadioPlaylist
            assertTrue("AC/DC radio should return tracks", data.tracks.isNotEmpty())
            println("    AC/DC: ${data.tracks.size} tracks (special chars OK)")
        } else {
            println("    AC/DC: ${radio?.let { it::class.simpleName }} (may be transient)")
        }
    }

    // ═══════════════════════════════════════════════════════
    // SIMILAR_ALBUMS EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `06 - similar albums - well-known album has valid scores and no self-reference`() = runBlocking {
        println("\n  --- OK Computer by Radiohead (SIMILAR_ALBUMS) ---")
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        val sa = results[EnrichmentType.SIMILAR_ALBUMS]
        assertTrue("Expected Success for OK Computer similar albums", sa is EnrichmentResult.Success)
        val data = (sa as EnrichmentResult.Success).data as EnrichmentData.SimilarAlbums

        assertTrue("Should return at least 1 similar album", data.albums.isNotEmpty())

        // Score range and no self-referencing
        data.albums.forEach { album ->
            assertTrue("Score out of range for ${album.title}: ${album.artistMatchScore}",
                album.artistMatchScore >= 0f)
            assertTrue("Album title should not be blank", album.title.isNotBlank())
            assertTrue("Album artist should not be blank", album.artist.isNotBlank())
        }

        // No seed artist's own albums in results (self-filter)
        val selfAlbums = data.albums.filter { it.artist.equals("Radiohead", ignoreCase = true) }
        assertTrue("Seed artist albums should be excluded: ${selfAlbums.map { it.title }}", selfAlbums.isEmpty())

        // No duplicates
        val dupes = data.albums.groupBy { "${it.title}:${it.artist}".lowercase() }.filter { it.value.size > 1 }
        assertTrue("Duplicate albums: ${dupes.keys}", dupes.isEmpty())

        println("    total: ${data.albums.size}, unique artists: ${data.albums.map { it.artist }.distinct().size}")
        println("    score range: ${data.albums.minOf { it.artistMatchScore }}..${data.albums.maxOf { it.artistMatchScore }}")
        data.albums.take(5).forEach {
            println("      ${it.title.take(35).padEnd(35)} ${it.artist.padEnd(20)} ${it.year ?: "?"} score=${it.artistMatchScore}")
        }
    }

    @Test
    fun `06b - similar albums - wrong request type returns NotFound`() = runBlocking {
        val results = engine.enrich(EnrichmentRequest.forArtist("Radiohead"), setOf(EnrichmentType.SIMILAR_ALBUMS))
        assertTrue(
            "ForArtist should return NotFound for SIMILAR_ALBUMS",
            results[EnrichmentType.SIMILAR_ALBUMS] is EnrichmentResult.NotFound,
        )
    }

    @Test
    fun `06c - similar albums - single-char album name does not crash`() = runBlocking {
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("I", "Meshuggah"),
            setOf(EnrichmentType.SIMILAR_ALBUMS),
        )
        val sa = results[EnrichmentType.SIMILAR_ALBUMS]
        // Single-char should not crash — any result type is acceptable
        assertNotNull("Result should not be null", sa)
        if (sa is EnrichmentResult.Success) {
            val data = sa.data as EnrichmentData.SimilarAlbums
            println("    I by Meshuggah: ${data.albums.size} similar albums")
        } else {
            println("    I by Meshuggah: ${sa?.let { it::class.simpleName }}")
        }
    }

    // ═══════════════════════════════════════════════════════
    // GENRE_DISCOVERY EDGE CASES
    // ═══════════════════════════════════════════════════════

    @Test
    fun `07 - genre discovery - well-known artist returns related genres with valid affinities`() = runBlocking {
        println("\n  --- Radiohead GENRE_DISCOVERY ---")
        val results = engine.enrich(
            EnrichmentRequest.forArtist("Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        val gd = results[EnrichmentType.GENRE_DISCOVERY]
        assertTrue("Expected Success for Radiohead genre discovery", gd is EnrichmentResult.Success)
        val data = (gd as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery

        assertTrue("Should return at least 1 related genre", data.relatedGenres.isNotEmpty())
        data.relatedGenres.forEach { genre ->
            assertTrue("Affinity out of range for ${genre.name}: ${genre.affinity}",
                genre.affinity in 0f..1.0f)
            assertTrue("Genre name should not be blank", genre.name.isNotBlank())
            assertTrue("Relationship should not be blank for ${genre.name}", genre.relationship.isNotBlank())
            assertTrue("Source genres should not be empty for ${genre.name}", genre.sourceGenres.isNotEmpty())
        }

        val byRel = data.relatedGenres.groupBy { it.relationship }
        println("    total: ${data.relatedGenres.size}, relationships: ${byRel.mapValues { it.value.size }}")
        println("    affinity range: ${data.relatedGenres.minOf { it.affinity }}..${data.relatedGenres.maxOf { it.affinity }}")
    }

    @Test
    fun `07b - genre discovery - works for ForAlbum and ForTrack`() = runBlocking {
        // ForAlbum should work (albums have genre data)
        val albumResults = engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        val albumGd = albumResults[EnrichmentType.GENRE_DISCOVERY]
        if (albumGd is EnrichmentResult.Success) {
            val data = albumGd.data as EnrichmentData.GenreDiscovery
            assertTrue("Album genre discovery should return genres", data.relatedGenres.isNotEmpty())
            println("    ForAlbum: ${data.relatedGenres.size} related genres")
        } else {
            println("    ForAlbum: ${albumGd?.let { it::class.simpleName }} (genres may not have resolved)")
        }

        // ForTrack — may or may not work depending on genre tag availability
        val trackResults = engine.enrich(
            EnrichmentRequest.forTrack("Creep", "Radiohead"),
            setOf(EnrichmentType.GENRE_DISCOVERY),
        )
        val trackGd = trackResults[EnrichmentType.GENRE_DISCOVERY]
        assertNotNull("ForTrack result should not be null", trackGd)
        println("    ForTrack: ${trackGd?.let { it::class.simpleName }}")
    }

    // ═══════════════════════════════════════════════════════
    // CONCURRENT ENRICHMENT (all v0.6.0 types at once)
    // ═══════════════════════════════════════════════════════

    @Test
    fun `08 - all v060 types concurrent - no errors under fan-out`() = runBlocking {
        println("\n  --- Daft Punk (5 types concurrent) ---")
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

        // All 5 types should be present in results (even if NotFound)
        val requestedTypes = setOf(
            EnrichmentType.SIMILAR_ARTISTS, EnrichmentType.ARTIST_RADIO,
            EnrichmentType.GENRE_DISCOVERY, EnrichmentType.GENRE, EnrichmentType.ARTIST_BIO,
        )
        for (type in requestedTypes) {
            assertNotNull("Missing result for $type", results[type])
            assertTrue(
                "$type should not be Error: ${(results[type] as? EnrichmentResult.Error)?.message}",
                results[type] !is EnrichmentResult.Error,
            )
        }

        println("    elapsed: ${elapsed}ms")
        results.forEach { (type, result) ->
            val status = when (result) {
                is EnrichmentResult.Success -> "OK (${result.provider})"
                is EnrichmentResult.NotFound -> "NF (${result.provider})"
                is EnrichmentResult.Error -> "ERR (${result.message})"
                is EnrichmentResult.RateLimited -> "RL"
            }
            println("    ${type.name.padEnd(20)} $status")
        }
    }

    @Test
    fun `08b - concurrent album types - no errors under fan-out`() = runBlocking {
        println("\n  --- Random Access Memories (3 types concurrent) ---")
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("Random Access Memories", "Daft Punk"),
            setOf(EnrichmentType.SIMILAR_ALBUMS, EnrichmentType.GENRE_DISCOVERY, EnrichmentType.GENRE),
        )

        for (type in results.keys) {
            assertTrue(
                "$type should not be Error: ${(results[type] as? EnrichmentResult.Error)?.message}",
                results[type] !is EnrichmentResult.Error,
            )
        }
        results.forEach { (type, result) ->
            println("    ${type.name.padEnd(20)} ${result::class.simpleName}")
        }
    }
}
