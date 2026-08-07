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
 * Enricher-level (mocked-HTTP) coverage for the qualifier-fallback search fix
 * (`.scratch/mb-search-parenthetical-qualifiers/issues/01-...`), exercising the wiring
 * [MusicBrainzQualifierFallbackTest] can't: multi-step search orchestration (original title tried
 * first, fallback only on a genuine miss), and the resulting `EnrichmentResult`.
 *
 * A qualifier-suffixed original title's search is deliberately left unstubbed everywhere it should
 * come back empty — [FakeHttpClient]'s unstubbed response is a 404, which
 * `HttpResult.bodyOrThrowTransient()` collapses to `null` and `MusicBrainzApi.searchReleases`/
 * `searchRecordings` collapse to an empty list, exactly mirroring MusicBrainz genuinely having no
 * release/recording literally titled that.
 */
class MusicBrainzQualifierFallbackIntegrationTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `album qualifier fallback resolves and the disambiguation tie-break picks the remastered release`() = runTest {
        // Given — the original "(Remastered)" title search comes up empty (left unstubbed), and
        // the stripped "Master Of Puppets" fallback search returns a same-score tie: one release
        // with no relevant disambiguation, one whose disambiguation actually says "remastered"
        httpClient.givenJsonResponse(
            "release%3A%22Master+Of+Puppets%22",
            """
            {
              "releases": [
                {
                  "id": "dcc-1999",
                  "score": 100,
                  "title": "Master of Puppets",
                  "disambiguation": "DCC Compact Classics",
                  "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}],
                  "release-group": {"id": "rg-1", "tags": [{"name": "metal", "count": 5}]}
                },
                {
                  "id": "remaster-2017",
                  "score": 100,
                  "title": "Master of Puppets",
                  "disambiguation": "remastered deluxe version",
                  "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}],
                  "release-group": {"id": "rg-1", "tags": [{"name": "metal", "count": 5}]}
                }
              ]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("Master Of Puppets (Remastered)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — the qualifier-matching release wins the tie, not the first-in-pool one
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("remaster-2017", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals("rg-1", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    @Test
    fun `album literal title resolves directly, with zero fallback searches`() = runTest {
        // Given — "2112 (deluxe edition)" IS Rush's literal MusicBrainz release title (verified
        // live), so the ORIGINAL exact search must already succeed
        httpClient.givenJsonResponse(
            "release%3A%222112+%5C%28deluxe+edition%5C%29%22",
            """
            {
              "releases": [{
                "id": "rush-2112-deluxe",
                "score": 100,
                "title": "2112 (deluxe edition)",
                "artist-credit": [{"artist": {"id": "art2", "name": "Rush"}}],
                "release-group": {"id": "rg-2112", "tags": [{"name": "rock", "count": 3}]}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("2112 (deluxe edition)", "Rush")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — resolved on the original title, and only one release search was ever made
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("rush-2112-deluxe", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(1, httpClient.requestedUrls.count { it.contains("release?query") })
    }

    @Test
    fun `album literal title regression, Abbey Road anniversary edition, also resolves with zero fallback`() = runTest {
        // Given — "Abbey Road (anniversary edition)" IS The Beatles' literal MusicBrainz release
        // title too (verified live), the second regression case the design decision names
        // explicitly alongside "2112 (deluxe edition)"
        httpClient.givenJsonResponse(
            "release%3A%22Abbey+Road+%5C%28anniversary+edition%5C%29%22",
            """
            {
              "releases": [{
                "id": "beatles-abbey-road-anniversary",
                "score": 100,
                "title": "Abbey Road (anniversary edition)",
                "artist-credit": [{"artist": {"id": "art3", "name": "The Beatles"}}],
                "release-group": {"id": "rg-abbey-road", "tags": [{"name": "rock", "count": 3}]}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("Abbey Road (anniversary edition)", "The Beatles")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("beatles-abbey-road-anniversary", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals(1, httpClient.requestedUrls.count { it.contains("release?query") })
    }

    @Test
    fun `qualifier-suffixed lookup resolves to the same release-group as the qualifier-free lookup`() = runTest {
        // Given — one stubbed pool, reachable both directly (bare title) and via fallback
        // (qualifier-suffixed title) — the contract is parity with the unmodified qualifier-free
        // path, not a new, independent proof of correctness
        httpClient.givenJsonResponse(
            "release%3A%22Master+Of+Puppets%22",
            """
            {
              "releases": [{
                "id": "mop-release",
                "score": 100,
                "title": "Master of Puppets",
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}],
                "release-group": {"id": "rg-mop", "tags": [{"name": "metal", "count": 5}]}
              }]
            }
            """.trimIndent(),
        )

        // When — the same album, once via the plain (already-working) path, once via the
        // qualifier-suffixed fallback path
        val bare = provider.enrich(EnrichmentRequest.forAlbum("Master Of Puppets", "Metallica"), EnrichmentType.GENRE)
        val qualified = provider.enrich(
            EnrichmentRequest.forAlbum("Master Of Puppets (Remastered)", "Metallica"), EnrichmentType.GENRE,
        )

        // Then — both resolve to the same release-group
        assertTrue(bare is EnrichmentResult.Success)
        assertTrue(qualified is EnrichmentResult.Success)
        assertEquals(
            (bare as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzReleaseGroupId,
            (qualified as EnrichmentResult.Success).resolvedIdentifiers?.musicBrainzReleaseGroupId,
        )
        assertEquals("rg-mop", qualified.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    @Test
    fun `album edition-ambiguity negative case stays NotFound, Live is not a recognized qualifier`() = runTest {
        // Given — the original "(Live)" title search comes up empty, and "Live" is deliberately
        // excluded from the qualifier vocabulary (docs on MusicBrainzQualifierFallback.KIND_PATTERNS),
        // so no fallback candidate is ever generated or searched
        httpClient.givenJsonResponse("release?query", """{"releases": []}""")
        val request = EnrichmentRequest.forAlbum("Master Of Puppets (Live)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound, and exactly one release search (the original) plus the fuzzy-suggestions
        // search — never a fallback search for a bare "Master Of Puppets" title
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(0, httpClient.requestedUrls.count { it.contains("release%3A%22Master+Of+Puppets%22") })
    }

    @Test
    fun `album multi-group qualifier strips as one fallback candidate`() = runTest {
        // Given — a single bracket group with two slash-separated qualifiers strips as one step
        httpClient.givenJsonResponse(
            "release%3A%22Master+Of+Puppets%22",
            """
            {
              "releases": [{
                "id": "box-set-release",
                "score": 100,
                "title": "Master of Puppets",
                "disambiguation": "deluxe box set",
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}],
                "release-group": {"id": "rg-1", "tags": [{"name": "metal", "count": 5}]}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("Master Of Puppets (Deluxe Box Set / Remastered)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("box-set-release", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `album qualifier fallback requires an artist match, not just a title match`() = runTest {
        // Given — the stripped-title pool's only hit is credited to a different artist entirely
        httpClient.givenJsonResponse(
            "release%3A%22Master+Of+Puppets%22",
            """
            {
              "releases": [{
                "id": "wrong-artist",
                "score": 100,
                "title": "Master of Puppets",
                "artist-credit": [{"artist": {"id": "someone-else", "name": "Some Tribute Band"}}],
                "release-group": {"id": "rg-x", "tags": [{"name": "metal", "count": 5}]}
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forAlbum("Master Of Puppets (Remastered)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — not authoritative, falls through to NotFound rather than a confident wrong match
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrichAlbumTracks resolves a qualifier-suffixed title via the same fallback`() = runTest {
        // Given — same stripped-title pool, single unambiguous hit
        httpClient.givenJsonResponse(
            "release%3A%22Master+Of+Puppets%22",
            """
            {
              "releases": [{
                "id": "resolved-release",
                "score": 100,
                "title": "Master of Puppets",
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}],
                "release-group": {"id": "rg-1"}
              }]
            }
            """.trimIndent(),
        )
        httpClient.givenJsonResponse(
            "release/resolved-release",
            """{"id": "resolved-release", "title": "Master of Puppets",
                "media": [{"tracks": [{"title": "Battery", "position": 1}]}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Master Of Puppets (Remastered)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `track qualifier fallback resolves Enter Sandman Remastered 2021 via musicbrainz TRACK_METADATA`() = runTest {
        // Given — the original "(Remastered 2021)" recording search comes up empty, and the
        // stripped "Enter Sandman" fallback returns a candidate pickBestRecording accepts, with a
        // credited artist matching the request (authoritative-match requirement)
        httpClient.givenJsonResponse(
            "recording%3A%22Enter+Sandman%22",
            """{"recordings": [{"id": "rec-studio", "score": 100, "title": "Enter Sandman", "length": 331000,
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}]}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman (Remastered 2021)", "Metallica")

        // When — TRACK_METADATA, matching the ticket's named acceptance criterion, rather than GENRE
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then — resolved via musicbrainz itself, not degraded to some other provider's fuzzy match
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("musicbrainz", success.provider)
        assertEquals("rec-studio", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `track qualifier fallback still rejects a pool with nothing pickBestRecording accepts`() = runTest {
        // Given — the stripped-title fallback pool exists but every candidate scores below threshold
        httpClient.givenJsonResponse(
            "recording%3A%22Enter+Sandman%22",
            """{"recordings": [{"id": "rec-low", "score": 50, "title": "Enter Sandman",
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}]}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman (Remastered 2021)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `track qualifier fallback rejects an exact-title match from the wrong artist`() = runTest {
        // Given — a score-100, title-exact recording exists, but it's credited to a different
        // artist than the request — score/title alone must not be treated as proof of identity
        httpClient.givenJsonResponse(
            "recording%3A%22Enter+Sandman%22",
            """{"recordings": [{"id": "rec-cover", "score": 100, "title": "Enter Sandman",
                "artist-credit": [{"artist": {"id": "art9", "name": "Apocalyptica"}}]}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman (Remastered 2021)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `track qualifier fallback rejects a same-score non-equal-title candidate`() = runTest {
        // Given — the fallback pool's own search for "Enter Sandman" returns a recording whose
        // normalized title doesn't equal the searched candidate — score alone is not proof of
        // identity, quoted Lucene is phrase search, not string equality
        httpClient.givenJsonResponse(
            "recording%3A%22Enter+Sandman%22",
            """{"recordings": [{"id": "rec-other", "score": 100, "title": "Enter Sandman (Live)",
                "artist-credit": [{"artist": {"id": "art1", "name": "Metallica"}}]}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman (Remastered 2021)", "Metallica")

        // When
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }
}
