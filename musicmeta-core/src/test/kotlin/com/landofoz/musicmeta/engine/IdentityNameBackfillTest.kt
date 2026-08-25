package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What identity resolution hands the name-search providers when the caller supplied no name, and
 * what it must never hand them when the caller did.
 *
 * A request from [EnrichmentRequest.forTrackByMbid] and its siblings names an identifier and
 * nothing else, and Deezer, LRCLIB, iTunes, Last.fm and Discogs cannot use one. Filling the blank
 * from the resolved entity is what makes them reachable; filling a field the caller wrote is the
 * defect two earlier efforts closed, so both directions are pinned here.
 */
class IdentityNameBackfillTest {

    private val httpClient = FakeHttpClient()
    private val cache = FakeEnrichmentCache()
    private val musicBrainz = MusicBrainzProvider(httpClient, RateLimiter(0))

    private fun namedProvider() = FakeProvider(
        id = "lyrics",
        capabilities = listOf(ProviderCapability(EnrichmentType.LYRICS_PLAIN, 100)),
    ).also {
        it.givenResult(
            EnrichmentType.LYRICS_PLAIN,
            EnrichmentResult.Success(
                EnrichmentType.LYRICS_PLAIN,
                EnrichmentData.Lyrics(plainLyrics = "Pressure, pushing down on me"),
                "lyrics",
                1f,
            ),
        )
    }

    private fun engine(other: FakeProvider) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(musicBrainz, other)),
        cache,
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a track request carrying only an mbid reaches the name providers with the resolved names`() = runTest {
        // Given - a recording MusicBrainz holds under the caller's identifier, credited to two artists
        httpClient.givenJsonResponse("recording/$UNDER_PRESSURE_MBID", UNDER_PRESSURE_RECORDING)
        val lyrics = namedProvider()

        // When - the request names that identifier and no title or artist at all
        engine(lyrics).enrich(
            EnrichmentRequest.forTrackByMbid(UNDER_PRESSURE_MBID),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - the lyrics provider was asked for the canonical title, and for the artist-credit
        // joined with MusicBrainz's own join phrase rather than its first artist alone
        val asked = lyrics.enrichCalls.first().first as EnrichmentRequest.ForTrack
        assertEquals("Under Pressure", asked.title)
        assertEquals("Queen & David Bowie", asked.artist)
    }

    @Test
    fun `a title the caller supplied survives identity resolution untouched`() = runTest {
        // Given - the same recording, whose canonical title differs from the one the caller asked for
        httpClient.givenJsonResponse("recording/$UNDER_PRESSURE_MBID", UNDER_PRESSURE_RECORDING)
        val lyrics = namedProvider()

        // When - the caller names the variant they meant alongside the identifier
        engine(lyrics).enrich(
            EnrichmentRequest.forTrack("Under Pressure (live at Wembley)", "Queen", mbid = UNDER_PRESSURE_MBID),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - the lyrics provider hunts for the variant the caller named, not the studio take
        val asked = lyrics.enrichCalls.first().first as EnrichmentRequest.ForTrack
        assertEquals("Under Pressure (live at Wembley)", asked.title)
        assertEquals("Queen", asked.artist)
    }

    @Test
    fun `an album request carrying only an mbid is filled from the release`() = runTest {
        // Given - a release MusicBrainz holds under the caller's identifier
        httpClient.givenJsonResponse("release/$RUSH_MBID", RUSH_RELEASE)
        val other = namedProvider()

        // When - the request names that identifier and no title or artist
        engine(other).enrich(EnrichmentRequest.forAlbumByMbid(RUSH_MBID), setOf(EnrichmentType.GENRE))

        // Then - the request the fan-out carries names the release and its artist-credit
        val canonical = EnrichmentRequest.forAlbum("A Rush of Blood to the Head", "Coldplay")
        assertTrue(cache.stored.keys.any { it == entityKeyForName(canonical, EnrichmentType.GENRE) + ":${EnrichmentType.GENRE}" })
    }

    @Test
    fun `an artist request carrying only an mbid is filled from the artist`() = runTest {
        // Given - an artist MusicBrainz holds under the caller's identifier
        httpClient.givenJsonResponse("artist/$QUEEN_MBID", QUEEN_ARTIST)
        val other = namedProvider()

        // When - the request names that identifier and no name
        engine(other).enrich(EnrichmentRequest.forArtistByMbid(QUEEN_MBID), setOf(EnrichmentType.GENRE))

        // Then - the result is aliased under the canonical artist name, which is the key a later
        // name-only lookup asks with
        val canonical = EnrichmentRequest.forArtist("Queen")
        assertTrue(cache.stored.keys.any { it == entityKeyForName(canonical, EnrichmentType.GENRE) + ":${EnrichmentType.GENRE}" })
    }

    @Test
    fun `an mbid MusicBrainz holds nothing under reaches no name-search provider at all`() = runTest {
        // Given - nothing stubbed, so every MusicBrainz lookup answers 404 as it does for a dead id
        val lyrics = namedProvider()

        // When - a request naming only that identifier is enriched
        val results = engine(lyrics).enrich(
            EnrichmentRequest.forTrackByMbid(DEAD_MBID),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - the lyrics provider was never asked: it searches by name, and there is no name
        assertEquals(0, lyrics.enrichCalls.count { it.second == EnrichmentType.LYRICS_PLAIN })
        // Then - the type is an honest NotFound rather than an Error or a guess
        assertTrue(results.raw[EnrichmentType.LYRICS_PLAIN] is EnrichmentResult.NotFound)
        // Then - nothing was cached under a name key naming nothing
        assertNull(cache.stored.keys.firstOrNull { it.startsWith("track:::") })
    }

    @Test
    fun `a title with no artist beside it reaches no name-search provider either`() = runTest {
        // Given - a recording pool for the title, and a caller who supplied no artist
        httpClient.givenJsonResponse("recording?query", RECORDING_POOL)
        val lyrics = namedProvider()

        // When - the request names the title alone
        val results = engine(lyrics).enrich(
            EnrichmentRequest.forTrack("Under Pressure", ""),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - the search is not run: MusicBrainz drops an empty artistname term rather than
        // refusing it, so the title alone would rank a pool nothing in the request can narrow
        assertTrue(httpClient.requestedUrls.none { it.contains("recording?query") })
        // Then - the name-search provider is not asked, exactly as for a request naming nothing
        assertEquals(0, lyrics.enrichCalls.count { it.second == EnrichmentType.LYRICS_PLAIN })
        // Then - the type is an honest NotFound rather than a guess wearing a hit's confidence
        assertTrue(results.raw[EnrichmentType.LYRICS_PLAIN] is EnrichmentResult.NotFound)
    }

    @Test
    fun `a later name-only lookup hits the alias the mbid request left`() = runTest {
        // Given - an album enriched by identifier alone, whose result is aliased under the
        // canonical name
        httpClient.givenJsonResponse("release/$RUSH_MBID", RUSH_RELEASE)
        val other = namedProvider()
        val engine = engine(other)
        engine.enrich(EnrichmentRequest.forAlbumByMbid(RUSH_MBID), setOf(EnrichmentType.GENRE))
        val spentOnFirst = httpClient.requestedUrls.size

        // When - the same album is asked for by that name, with no identifier
        val results = engine.enrich(
            EnrichmentRequest.forAlbum("A Rush of Blood to the Head", "Coldplay"),
            setOf(EnrichmentType.GENRE),
        )

        // Then - it is served from the alias: a Success, and not one upstream request more
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        assertEquals(spentOnFirst, httpClient.requestedUrls.size)
    }

    @Test
    fun `invalidating an mbid request reaches the canonical alias too`() = runTest {
        // Given - that same alias, sitting in the cache under the canonical name
        httpClient.givenJsonResponse("release/$RUSH_MBID", RUSH_RELEASE)
        val other = namedProvider()
        val engine = engine(other)
        val byMbid = EnrichmentRequest.forAlbumByMbid(RUSH_MBID)
        engine.enrich(byMbid, setOf(EnrichmentType.GENRE))

        // When - the caller invalidates the request as they made it, by identifier
        engine.invalidate(byMbid, EnrichmentType.GENRE)

        // Then - the name key is gone as well as the identifier key, so a name-only lookup misses
        // rather than being served an entry its owner believes they dropped
        assertTrue(cache.stored.keys.none { it.startsWith("album:Coldplay:A Rush of Blood to the Head") })
        assertTrue(cache.stored.keys.none { it.startsWith("album:$RUSH_MBID") })
    }

    private companion object {
        const val UNDER_PRESSURE_MBID = "6f903161-8757-4363-a6ab-54bfe1149bb8"
        const val RUSH_MBID = "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4"
        const val QUEEN_MBID = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3"
        const val DEAD_MBID = "60d043af-b702-30d9-b6c0-0688d7863b14"

        // captured 2026-08-12: GET /ws/2/recording/6f903161-...?inc=artists+releases+release-groups+isrcs, trimmed
        const val UNDER_PRESSURE_RECORDING = """
            {
              "id": "6f903161-8757-4363-a6ab-54bfe1149bb8",
              "title": "Under Pressure",
              "length": 235400,
              "isrcs": ["GBUM71106916"],
              "artist-credit": [
                {
                  "name": "Queen",
                  "joinphrase": " & ",
                  "artist": { "id": "0383dadf-2a4e-4d10-a46a-e9e041da8eb3", "name": "Queen" }
                },
                {
                  "name": "David Bowie",
                  "joinphrase": "",
                  "artist": { "id": "5441c29d-3602-4898-b1a1-b77fa23b8e50", "name": "David Bowie" }
                }
              ],
              "releases": [
                {
                  "id": "46a1cce2-a0a8-4816-9c7c-f48e0537032b",
                  "title": "My Generation - The Soundtrack of Our Lives, Part One",
                  "date": "2005-10",
                  "release-group": {
                    "id": "13ed01fd-af02-3030-afc1-17e62c53eb02",
                    "title": "My Generation - The Soundtrack of Our Lives, Part One",
                    "primary-type": "Album",
                    "first-release-date": "2005-10"
                  }
                }
              ]
            }
        """

        // captured 2026-08-12: GET /ws/2/recording?query=recording:"Under Pressure" AND artistname:""
        // AND -comment:*, trimmed to the first hit — the artist-less query MusicBrainz answers with
        // 1081 hits, none of them Queen's
        const val RECORDING_POOL = """
            {
              "count": 1081,
              "recordings": [
                {
                  "id": "a2a656a5-e15a-4364-ab05-275f6ade9786",
                  "score": 100,
                  "title": "Under Pressure",
                  "length": 267933,
                  "artist-credit": [
                    {
                      "name": "Joss Stone",
                      "joinphrase": "",
                      "artist": { "id": "dbbc47a5-1338-4830-9298-a8d0b11c0a46", "name": "Joss Stone" }
                    }
                  ],
                  "releases": [
                    {
                      "id": "144526f8-40ab-49b0-85d5-fc28694be43e",
                      "title": "Killer Queen: A Tribute to Queen",
                      "status": "Official",
                      "release-group": {
                        "id": "422e196f-52d0-360c-8a8d-94f9aed28ef3",
                        "title": "Killer Queen: A Tribute to Queen",
                        "primary-type": "Album"
                      }
                    }
                  ]
                }
              ]
            }
        """

        // captured 2026-08-12: GET /ws/2/release/4ebc64a2-...?inc=artist-credits+labels+release-groups+genres, trimmed
        const val RUSH_RELEASE = """
            {
              "id": "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4",
              "title": "A Rush of Blood to the Head",
              "date": "2008-06-11",
              "country": "JP",
              "barcode": "4988006846395",
              "status": "Official",
              "artist-credit": [
                {
                  "name": "Coldplay",
                  "joinphrase": "",
                  "artist": { "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234", "name": "Coldplay" }
                }
              ],
              "release-group": {
                "id": "120c786d-a3b2-3c19-b4ff-2b7b3b4435bf",
                "title": "A Rush of Blood to the Head",
                "primary-type": "Album",
                "secondary-types": []
              },
              "label-info": [ { "label": { "name": "Parlophone" } } ],
              "genres": [ { "name": "alternative rock", "count": 9 } ]
            }
        """

        // captured 2026-08-12: GET /ws/2/artist/0383dadf-...?inc=tags+genres+aliases+url-rels, trimmed
        const val QUEEN_ARTIST = """
            {
              "id": "0383dadf-2a4e-4d10-a46a-e9e041da8eb3",
              "name": "Queen",
              "sort-name": "Queen",
              "type": "Group",
              "country": "GB",
              "disambiguation": "UK rock group",
              "life-span": { "begin": "1970-06-27" },
              "genres": [ { "name": "art rock", "count": 15 } ]
            }
        """
    }
}
