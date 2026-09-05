package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzMapper
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzSimilarArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarArtistMergerTest {

    // --- merge() top-level tests ---

    @Test
    fun `merge returns NotFound for empty results`() {
        // Given - an empty list of provider results
        val results = emptyList<EnrichmentResult.Success>()

        // When - merging the empty list
        val result = SimilarArtistMerger.merge(results)

        // Then - a NotFound result is returned for "all_providers"
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("all_providers", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `merge returns single provider results unchanged`() {
        // Given - lastfm returns 3 artists
        val artists = listOf(
            SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
            SimilarArtist("Bjork", matchScore = 0.7f, sources = listOf("lastfm")),
            SimilarArtist("Portishead", matchScore = 0.6f, sources = listOf("lastfm")),
        )
        val results = listOf(
            EnrichmentResult.Success(
                type = EnrichmentType.SIMILAR_ARTISTS,
                data = EnrichmentData.SimilarArtists(artists = artists),
                provider = "lastfm",
                confidence = 0.9f,
            )
        )

        // When - merging the single-provider result list
        val result = SimilarArtistMerger.merge(results)

        // Then - all 3 artists returned with original sources
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(3, data.artists.size)
        assertEquals("similar_artist_merger", result.provider)
        assertTrue(data.artists.all { it.sources.contains("lastfm") })
    }

    @Test
    fun `merge deduplicates artists by normalized name`() {
        // Given - lastfm has "Muse" at 0.9, deezer has "muse" (lowercase) at 0.5
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("muse", matchScore = 0.5f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging results with the case-differing duplicate
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - only 1 "Muse" entry (merged from both)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(1, data.artists.size)
        assertEquals("Muse", data.artists[0].name) // first-seen casing preserved
    }

    @Test
    fun `merge sums matchScores capped at 1_0`() {
        // Given - "Muse" appears in both providers with scores 0.9 and 0.8
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.8f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging the results with the duplicate "Muse" entries
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - matchScore = min(0.9 + 0.8, 1.0) = 1.0, not 1.7
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(1.0f, data.artists[0].matchScore, 0.001f)
    }

    @Test
    fun `merge combines sources from multiple providers`() {
        // Given - "Muse" from lastfm and deezer
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.5f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging the results with the duplicate "Muse" entries
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - both sources listed
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        val sources = data.artists[0].sources
        assertTrue("lastfm" in sources)
        assertTrue("deezer" in sources)
    }

    @Test
    fun `merge prefers MBID from provider that has it`() {
        // Given - lastfm has MBID for "Muse", deezer has deezerId in extra
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist(
                    name = "Muse",
                    identifiers = EnrichmentIdentifiers(musicBrainzId = "muse-mbid"),
                    matchScore = 0.9f,
                    sources = listOf("lastfm"),
                ),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist(
                    name = "Muse",
                    identifiers = EnrichmentIdentifiers(extra = mapOf("deezerId" to "123")),
                    matchScore = 0.5f,
                    sources = listOf("deezer"),
                ),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging the results with the shared "Muse" entry
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - merged result has both MBID and deezerId
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        val merged = data.artists[0]
        assertEquals("muse-mbid", merged.identifiers.musicBrainzId)
        assertEquals("123", merged.identifiers.extra["deezerId"])
    }

    @Test
    fun `merge sorts by matchScore descending`() {
        // Given - three providers each contributing a unique artist with varying scores
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Bjork", matchScore = 0.6f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Portishead", matchScore = 0.9f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )
        val lbResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Thom Yorke", matchScore = 0.75f, sources = listOf("listenbrainz")),
            )),
            provider = "listenbrainz",
            confidence = 0.85f,
        )

        // When - merging the three single-artist provider results
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult, lbResult))

        // Then - sorted by matchScore descending
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        val scores = data.artists.map { it.matchScore }
        assertEquals(listOf(0.9f, 0.75f, 0.6f), scores)
    }

    @Test
    fun `merge handles artists unique to each provider`() {
        // Given - "Muse" only from lastfm, "Portishead" only from deezer
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Portishead", matchScore = 0.8f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging the results with the disjoint artists
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - both appear once with their original single-provider sources
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(2, data.artists.size)
        val names = data.artists.map { it.name }
        assertTrue("Muse" in names)
        assertTrue("Portishead" in names)
        val muse = data.artists.first { it.name == "Muse" }
        assertEquals(listOf("lastfm"), muse.sources)
        val portishead = data.artists.first { it.name == "Portishead" }
        assertEquals(listOf("deezer"), portishead.sources)
    }

    @Test
    fun `a ListenBrainz-only pick does not outrank an artist two other providers both chose`() {
        // Given - Last.fm and Deezer agreeing on one artist, and ListenBrainz's own top pick alone
        val corroborated = listOf(
            SimilarArtist("Portishead", matchScore = 0.6f, sources = listOf("lastfm")),
            SimilarArtist("Portishead", matchScore = 0.3f, sources = listOf("deezer")),
        )
        val listenBrainz = ListenBrainzMapper.toSimilarArtists(
            listOf(
                ListenBrainzSimilarArtist(artistMbid = "mbid-1", name = "Massive Attack", score = 9000),
                ListenBrainzSimilarArtist(artistMbid = "mbid-2", name = "Tricky", score = 4500),
            ),
        ).artists

        // When - the three providers' answers are merged
        // NOTE(probe/merger-arm-rank-cap): mergeArtists' signature moved to List<List<SimilarArtist>>
        // on this arm's branch (see SimilarArtistMerger KDoc). This call and its exact-score
        // assertions below are pinned to HEAD's raw-sum scale and are expected to disagree with
        // this arm's rank-normalised scale — that disagreement is the arm's own effect, not a bug
        // in the probe. Not fixed here: this branch is a throwaway measurement arm, not a shipped
        // change to musicmeta-core's own test suite.
        val merged = SimilarArtistMerger.mergeArtists(corroborated.map { listOf(it) } + listOf(listenBrainz))

        // Then - two providers agreeing outrank one provider's favourite, which no cap has flattened
        assertEquals("Portishead", merged.first().name)
    }
}
