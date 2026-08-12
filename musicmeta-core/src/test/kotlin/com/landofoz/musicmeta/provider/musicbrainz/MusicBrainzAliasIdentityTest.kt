package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An artist named by one of its aliases has to resolve, and the confidence has to say that is what
 * happened. Both halves are read off the same real search response, because all three names below
 * genuinely reach this artist upstream.
 */
class MusicBrainzAliasIdentityTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_COLDPLAY)
        httpClient.givenJsonResponse("artist/cc197bad", ARTIST_LOOKUP_COLDPLAY)
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    private suspend fun resolve(name: String): EnrichmentResult.Success {
        val result = provider.enrich(EnrichmentRequest.forArtist(name), EnrichmentType.GENRE)
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
        return result as EnrichmentResult.Success
    }

    @Test
    fun `an artist named only by an alias resolves to that artist`() = runTest {
        // Given - a request naming "Coolplay", which reaches this artist through its alias index only
        val name = "Coolplay"

        // When - resolving the artist
        val success = resolve(name)

        // Then - it resolves to Coldplay's MBID
        assertEquals("cc197bad-dc9c-440d-a5b5-d52ba2e14234", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `the artist's own name resolves at the full search score`() = runTest {
        // Given - a request naming the artist itself, matched by MusicBrainz at score 100
        val name = "Coldplay"

        // When - resolving the artist
        val success = resolve(name)

        // Then - nothing scales the score down, so a canonical hit is a confident one
        assertEquals(1.0f, success.confidence, 0.001f)
    }

    @Test
    fun `a localised alias reports lower confidence than the artist's own name`() = runTest {
        // Given - a request naming the artist's Japanese alias, which carries a locale
        val name = "コールドプレイ"

        // When - resolving the artist
        val success = resolve(name)

        // Then - the same artist, at the primary-alias tier rather than the canonical one
        assertEquals("cc197bad-dc9c-440d-a5b5-d52ba2e14234", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(0.95f, success.confidence, 0.001f)
    }

    @Test
    fun `a search-hint alias reports lower confidence than a localised one`() = runTest {
        // Given - "Coolplay", a misspelling MusicBrainz keeps as a "Search hint" alias
        val name = "Coolplay"

        // When - resolving the artist
        val success = resolve(name)

        // Then - the weakest of the three tiers, so a consumer can tell a hint from a name
        assertEquals(0.85f, success.confidence, 0.001f)
    }

    @Test
    fun `a locale-tagged search hint stays at the search-hint tier`() = runTest {
        // Given - the same alias carrying a locale, which alone would promote it to primary
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_LOCALE_TAGGED_HINT)

        // When - resolving the artist by that alias
        val success = resolve("Coolplay")

        // Then - the alias type decides: a hint MusicBrainz keeps for its indexer is not a name
        assertEquals(0.85f, success.confidence, 0.001f)
    }

    @Test
    fun `the search asks MusicBrainz for aliases as well as names`() = runTest {
        // Given - a request whose name is only reachable through the alias index
        val name = "Coolplay"

        // When - resolving the artist
        resolve(name)

        // Then - the query carries both fields, since artist:"…" alone returns nothing for it
        val query = httpClient.requestedUrls.first { it.contains("artist?query") }
        assertTrue(query, query.contains("artist%3A%22Coolplay%22+OR+alias%3A%22Coolplay%22"))
    }

    private companion object {
        // captured 2026-08-12: GET /artist?query=artist:"Cold Play" OR alias:"Cold Play"&limit=5,
        // trimmed to the one hit and to the fields the parser reads
        const val ARTIST_SEARCH_COLDPLAY = """
        {
          "created": "2026-08-11T18:47:58.820Z",
          "count": 1,
          "offset": 0,
          "artists": [
            {
              "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234",
              "type": "Group",
              "score": 100,
              "name": "Coldplay",
              "sort-name": "Coldplay",
              "country": "GB",
              "life-span": { "begin": "1996-09", "ended": null },
              "aliases": [
                {
                  "sort-name": "Coolplay", "name": "Coolplay", "locale": null,
                  "type": "Search hint", "primary": null, "begin-date": null, "end-date": null
                },
                {
                  "sort-name": "コールドプレイ", "name": "コールドプレイ", "locale": "ja",
                  "type": "Artist name", "primary": null, "begin-date": null, "end-date": null
                },
                {
                  "sort-name": "Cold Play", "name": "Cold Play", "locale": null,
                  "type": "Search hint", "primary": null, "begin-date": null, "end-date": null
                }
              ],
              "tags": [
                { "count": 34, "name": "alternative rock" },
                { "count": 13, "name": "pop" }
              ]
            }
          ]
        }
        """

        // synthetic: the captured search hit with a locale added to its "Search hint" alias — the
        // combination MusicBrainz allows and this capture does not happen to contain
        const val ARTIST_SEARCH_LOCALE_TAGGED_HINT = """
        {
          "count": 1,
          "artists": [
            {
              "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234",
              "type": "Group",
              "score": 100,
              "name": "Coldplay",
              "sort-name": "Coldplay",
              "country": "GB",
              "aliases": [
                {
                  "sort-name": "Coolplay", "name": "Coolplay", "locale": "en",
                  "type": "Search hint", "primary": true, "begin-date": null, "end-date": null
                }
              ],
              "tags": [ { "count": 34, "name": "alternative rock" } ]
            }
          ]
        }
        """

        // captured 2026-08-12: GET /artist/cc197bad-dc9c-440d-a5b5-d52ba2e14234
        // ?inc=tags+genres+aliases+ratings+url-rels+artist-rels, trimmed
        const val ARTIST_LOOKUP_COLDPLAY = """
        {
          "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234",
          "name": "Coldplay",
          "sort-name": "Coldplay",
          "type": "Group",
          "country": "GB",
          "disambiguation": "",
          "life-span": { "begin": "1996-09", "end": null, "ended": false },
          "rating": { "value": 4.05, "votes-count": 49 },
          "aliases": [
            {
              "name": "Cold Play", "sort-name": "Cold Play", "locale": null,
              "type": "Search hint", "primary": null, "begin": null, "end": null, "ended": false
            }
          ],
          "tags": [
            { "count": 34, "name": "alternative rock" },
            { "count": 13, "name": "pop" },
            { "count": 10, "name": "british" },
            { "count": 0, "name": "parlophone" }
          ],
          "genres": [
            { "count": 34, "name": "alternative rock", "id": "ceeaa283-5d7b-4202-8d1d-e25d116b2a18", "disambiguation": "" },
            { "count": 13, "name": "pop", "id": "911c7bbb-172d-4df8-9478-dbff4296e791", "disambiguation": "" }
          ],
          "relations": []
        }
        """
    }
}
