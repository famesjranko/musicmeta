package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A recording search pool that ties or outranks a wrong-artist hit above the correctly-credited
 * one must not resolve `RESOLVED` to the wrong artist — [MusicBrainzEnricher.pickBestRecording]'s
 * direct search path now carries an `ArtistMatcher.matchQuality` tier over `artist-credit`
 * ([MusicBrainzRecording.artistCredits]), the same primitive `MusicBrainzReleaseRanking` uses on
 * the album path. Ranks rather than rejects: a pool with no matching-artist candidate at all still
 * resolves to its best title/edition match.
 *
 * [POOL_TIED_WRONG_ARTIST_FIRST] and [POOL_TIERED_WRONG_ARTIST_WINS] are modelled field-for-field
 * on [IdentityNameBackfillTest.RECORDING_POOL] and `.UNDER_PRESSURE_RECORDING`, both captured live
 * against MusicBrainz.
 */
class MusicBrainzWrongArtistTrackRankingTest {
    private val httpClient = FakeHttpClient()
    private val cache = FakeEnrichmentCache()

    private fun engine() =
        DefaultEnrichmentEngine(
            ProviderRegistry(listOf(MusicBrainzProvider(httpClient, RateLimiter(0)))),
            cache,
            EnrichmentConfig(),
            mergers = emptyList(),
        )

    @Test
    fun `wrong-artist hit tied and listed first does not beat the matching-artist hit`() =
        runTest {
            // Given - two score-100 "Under Pressure" hits, tied on every pre-existing pickBestRecording
            // tier (exact title, no album hint, not video, blank disambiguation, official album) — the
            // wrong-artist hit (Joss Stone's cover) listed first, the matching-artist hit (Queen &
            // David Bowie's original) listed second
            httpClient.givenJsonResponse("recording?query", POOL_TIED_WRONG_ARTIST_FIRST)

            // When - the request names both title and artist, with no identifier at all
            val results =
                engine().enrich(
                    EnrichmentRequest.forTrack("Under Pressure", "Queen"),
                    setOf(EnrichmentType.TRACK_METADATA),
                )

            // Then - identity resolves to the matching-artist recording, not the tied wrong-artist hit
            // that a position-only tiebreak would have picked
            assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
            assertEquals("6f903161-8757-4363-a6ab-54bfe1149bb8", results.identity.identifiers.musicBrainzId)
        }

    @Test
    fun `wrong-artist hit with a clean tier does not beat a matching-artist hit with a worse tier`() =
        runTest {
            // Given - the wrong-artist hit keeps a blank disambiguation (the best tier); the
            // matching-artist hit is a genuine recording of the same track disambiguated as a live
            // performance, so it loses the pre-existing disambiguation tier for a reason unrelated to
            // artist identity — the wrong-artist hit is listed second, so position is not doing the work
            httpClient.givenJsonResponse("recording?query", POOL_TIERED_WRONG_ARTIST_WINS)

            // When - the request names both title and artist, with no identifier at all
            val results =
                engine().enrich(
                    EnrichmentRequest.forTrack("Under Pressure", "Queen"),
                    setOf(EnrichmentType.TRACK_METADATA),
                )

            // Then - identity resolves to the matching-artist recording outright, on the artist tier
            // outranking the disambiguation tier, not on list position
            assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
            assertEquals("6f903161-8757-4363-a6ab-54bfe1149bb8", results.identity.identifiers.musicBrainzId)
        }

    @Test
    fun `a request naming one credited artist matches a collaboration over a solo credit with a worse tier`() =
        runTest {
            // Given - a solo "Queen" credit with a clean artist tier but a worse disambiguation tier,
            // against the real "Queen & David Bowie" collaboration credit with a blank disambiguation.
            // Per-credit matching ties both at QUALITY_SAME_NAME (the solo credit matches outright;
            // the collaboration's "Queen" entry matches outright too, independent of "David Bowie"),
            // so the disambiguation tier decides. A joined-string comparison ("Queen & David Bowie")
            // would instead score the collaboration QUALITY_CONTAINS — lower than the solo credit's
            // QUALITY_SAME_NAME — and let the solo credit win on the artist tier alone despite its
            // worse disambiguation; that would be this test failing
            httpClient.givenJsonResponse("recording?query", POOL_SOLO_ARTIST_VS_COLLABORATION)

            // When - the request names only "Queen"
            val results =
                engine().enrich(
                    EnrichmentRequest.forTrack("Under Pressure", "Queen"),
                    setOf(EnrichmentType.TRACK_METADATA),
                )

            // Then - the collaboration wins, which only a per-credit comparison explains
            assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
            assertEquals("6f903161-8757-4363-a6ab-54bfe1149bb8", results.identity.identifiers.musicBrainzId)
        }

    @Test
    fun `a non-Latin artist name still resolves when it ties a Latin-script decoy at QUALITY_NONE`() =
        runTest {
            // Given - two candidates above the score floor, neither of which ArtistMatcher.matchQuality
            // can compare favourably against the request's romanization: the real native-script credit
            // (which shares no characters with "Tokyo Jihen" after normalization) and a Latin-script
            // decoy credited to an unrelated band. Both tie at QUALITY_NONE, so the artist tier is
            // genuinely inert here, not merely unopposed — the blank-disambiguation tier decides
            httpClient.givenJsonResponse("recording?query", POOL_NATIVE_SCRIPT_TIES_LATIN_DECOY)

            // When - the request romanizes the artist's name, which neither candidate's credit does
            val results =
                engine().enrich(
                    EnrichmentRequest.forTrack("透明人間", "Tokyo Jihen"),
                    setOf(EnrichmentType.TRACK_METADATA),
                )

            // Then - the correctly-credited candidate still resolves — the artist tier ties both
            // candidates rather than filtering either out, so rejecting this population stays a
            // separate, deferred question from ranking's inertness here
            assertEquals(CanonicalStatus.RESOLVED, results.identity.status)
            assertEquals("f2c15f97-5a8b-4b4e-9b1a-2f6b6c8f9a11", results.identity.identifiers.musicBrainzId)
        }

    private companion object {
        const val POOL_TIED_WRONG_ARTIST_FIRST = """
            {
              "count": 187,
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
                },
                {
                  "id": "6f903161-8757-4363-a6ab-54bfe1149bb8",
                  "score": 100,
                  "title": "Under Pressure",
                  "length": 235400,
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
                      "title": "Greatest Hits II",
                      "status": "Official",
                      "release-group": {
                        "id": "13ed01fd-af02-3030-afc1-17e62c53eb02",
                        "title": "Greatest Hits II",
                        "primary-type": "Album"
                      }
                    }
                  ]
                }
              ]
            }
        """
        const val POOL_TIERED_WRONG_ARTIST_WINS = """
            {
              "count": 187,
              "recordings": [
                {
                  "id": "6f903161-8757-4363-a6ab-54bfe1149bb8",
                  "score": 100,
                  "title": "Under Pressure",
                  "length": 235400,
                  "disambiguation": "live, 1992-04-20: The Freddie Mercury Tribute Concert, Wembley Stadium, London, UK",
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
                      "title": "The Freddie Mercury Tribute Concert",
                      "status": "Official",
                      "release-group": {
                        "id": "13ed01fd-af02-3030-afc1-17e62c53eb02",
                        "title": "The Freddie Mercury Tribute Concert",
                        "primary-type": "Album"
                      }
                    }
                  ]
                },
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

        /**
         * A solo "Queen" credit with a clean disambiguation but a worse edition than the real
         * "Queen & David Bowie" collaboration credit ([POOL_TIED_WRONG_ARTIST_FIRST]'s
         * matching-artist recording, reused verbatim). Discriminates a per-credit
         * `ArtistMatcher.matchQuality` comparison from a joined-string one — see the test that
         * consumes this pool for the reasoning. Hand-assembled, not a captured response.
         */
        const val POOL_SOLO_ARTIST_VS_COLLABORATION = """
            {
              "count": 2,
              "recordings": [
                {
                  "id": "5c6d7e8f-9a0b-41c2-83d4-e5f6a7b8c9d0",
                  "score": 100,
                  "title": "Under Pressure",
                  "length": 200000,
                  "disambiguation": "live version",
                  "artist-credit": [
                    {
                      "name": "Queen",
                      "joinphrase": "",
                      "artist": { "id": "0383dadf-2a4e-4d10-a46a-e9e041da8eb3", "name": "Queen" }
                    }
                  ],
                  "releases": [
                    {
                      "id": "9f8e7d6c-5b4a-4392-8102-f3e4d5c6b7a8",
                      "title": "Queen Live",
                      "status": "Official",
                      "release-group": {
                        "id": "1a2b3c4d-5e6f-4708-9a0b-1c2d3e4f5a6b",
                        "title": "Queen Live",
                        "primary-type": "Album"
                      }
                    }
                  ]
                },
                {
                  "id": "6f903161-8757-4363-a6ab-54bfe1149bb8",
                  "score": 100,
                  "title": "Under Pressure",
                  "length": 235400,
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
                      "title": "Greatest Hits II",
                      "status": "Official",
                      "release-group": {
                        "id": "13ed01fd-af02-3030-afc1-17e62c53eb02",
                        "title": "Greatest Hits II",
                        "primary-type": "Album"
                      }
                    }
                  ]
                }
              ]
            }
        """

        /**
         * The real native-script credit ("東京事変" — Tokyo Jihen) that resolves correctly today by
         * accident, tied at the score floor against a Latin-script decoy credited to an unrelated
         * band — neither shares enough with the request's romanization for
         * `ArtistMatcher.matchQuality` to prefer one over the other, so both sit at `QUALITY_NONE`
         * and the blank-disambiguation tier decides. Hand-assembled, not a captured response; shaped
         * on the same schema [POOL_TIED_WRONG_ARTIST_FIRST] carries.
         */
        const val POOL_NATIVE_SCRIPT_TIES_LATIN_DECOY = """
            {
              "count": 2,
              "recordings": [
                {
                  "id": "f2c15f97-5a8b-4b4e-9b1a-2f6b6c8f9a11",
                  "score": 100,
                  "title": "透明人間",
                  "length": 240000,
                  "artist-credit": [
                    {
                      "name": "東京事変",
                      "joinphrase": "",
                      "artist": { "id": "9b6b1c2e-1111-4222-8333-444455556666", "name": "東京事変" }
                    }
                  ],
                  "releases": [
                    {
                      "id": "c1a2b3c4-d5e6-47f8-a9b0-c1d2e3f4a5b6",
                      "title": "教育",
                      "status": "Official",
                      "release-group": {
                        "id": "d2b3c4d5-e6f7-48a9-b0c1-d2e3f4a5b6c7",
                        "title": "教育",
                        "primary-type": "Album"
                      }
                    }
                  ]
                },
                {
                  "id": "a4b5c6d7-e8f9-4a0b-81c2-d3e4f5a6b7c8",
                  "score": 100,
                  "title": "透明人間",
                  "length": 238000,
                  "disambiguation": "remix",
                  "artist-credit": [
                    {
                      "name": "Some Other Band",
                      "joinphrase": "",
                      "artist": { "id": "b5c6d7e8-f9a0-4b1c-92d3-e4f5a6b7c8d9", "name": "Some Other Band" }
                    }
                  ],
                  "releases": [
                    {
                      "id": "c6d7e8f9-a0b1-4c2d-a3e4-f5a6b7c8d9e0",
                      "title": "Unrelated Remixes",
                      "status": "Official",
                      "release-group": {
                        "id": "d7e8f9a0-b1c2-4d3e-b4f5-a6b7c8d9e0f1",
                        "title": "Unrelated Remixes",
                        "primary-type": "Album"
                      }
                    }
                  ]
                }
              ]
            }
        """
    }
}
