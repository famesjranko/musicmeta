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
    fun `merge returns every artist from a single provider result`() {
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
    fun `merge rescales summed matchScores against the merged maximum`() {
        // Given - "Muse" in both providers summing to 1.7, and "Bjork" alone at half that sum
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
                SimilarArtist("Bjork", matchScore = 0.85f, sources = listOf("lastfm")),
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

        // Then - the 1.7 sum becomes 1.0 and Bjork keeps its 0.85/1.7 share instead of being flattened
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(listOf("Muse", "Bjork"), data.artists.map { it.name })
        assertEquals(1.0f, data.artists[0].matchScore, 0.0001f)
        assertEquals(0.5f, data.artists[1].matchScore, 0.0001f)
    }

    @Test
    fun `two artists whose sums both exceed 1_0 keep distinct scores`() {
        // Given - two artists both picked by two providers, summing to 1.7 and 1.6
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.9f, sources = listOf("lastfm")),
                SimilarArtist("Bjork", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_ARTISTS,
            data = EnrichmentData.SimilarArtists(artists = listOf(
                SimilarArtist("Muse", matchScore = 0.8f, sources = listOf("deezer")),
                SimilarArtist("Bjork", matchScore = 0.7f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging the two providers' answers
        val result = SimilarArtistMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - the stronger sum ranks first and the weaker one does not tie with it
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(listOf("Muse", "Bjork"), data.artists.map { it.name })
        assertEquals(1.0f, data.artists[0].matchScore, 0.0001f)
        assertEquals(1.6f / 1.7f, data.artists[1].matchScore, 0.0001f)
    }

    @Test
    fun `an all-zero merged list stays at zero rather than dividing by its own maximum`() {
        // Given - every contributor's entries score zero, so the merged maximum is zero
        val artists = listOf(
            SimilarArtist("Muse", matchScore = 0f, sources = listOf("lastfm")),
            SimilarArtist("Portishead", matchScore = 0f, sources = listOf("deezer")),
        )

        // When - merging the all-zero list
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - both scores are still zero, and none is NaN or infinite
        assertEquals(2, merged.size)
        assertTrue(merged.all { it.matchScore.isFinite() })
        assertEquals(0f, merged[0].matchScore, 0.0f)
        assertEquals(0f, merged[1].matchScore, 0.0f)
    }

    @Test
    fun `a single contributor's order survives the rescale`() {
        // Given - one provider's three artists, none of them at 1.0
        val artists = listOf(
            SimilarArtist("Muse", matchScore = 0.87f, sources = listOf("lastfm")),
            SimilarArtist("Bjork", matchScore = 0.435f, sources = listOf("lastfm")),
            SimilarArtist("Portishead", matchScore = 0.087f, sources = listOf("lastfm")),
        )

        // When - merging that single contributor's list
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - the provider's order is kept while every score is rescaled onto a 1.0 top
        assertEquals(listOf("Muse", "Bjork", "Portishead"), merged.map { it.name })
        assertEquals(1.0f, merged[0].matchScore, 0.0001f)
        assertEquals(0.5f, merged[1].matchScore, 0.0001f)
        assertEquals(0.1f, merged[2].matchScore, 0.0001f)
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

        // Then - sorted by matchScore descending, each score rescaled against the 0.9 top
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(listOf("Portishead", "Thom Yorke", "Bjork"), data.artists.map { it.name })
        assertEquals(1.0f, data.artists[0].matchScore, 0.0001f)
        assertEquals(0.75f / 0.9f, data.artists[1].matchScore, 0.0001f)
        assertEquals(0.6f / 0.9f, data.artists[2].matchScore, 0.0001f)
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
        val merged = SimilarArtistMerger.mergeArtists(corroborated + listenBrainz)

        // Then - two providers agreeing outrank one provider's favourite, at 0.9 and 0.5 rescaled by the 0.9 top
        assertEquals("Portishead", merged.first().name)
        assertEquals(1.0f, merged.first().matchScore, 0.0001f)
        assertEquals(0.5f / 0.9f, merged.first { it.name == "Massive Attack" }.matchScore, 0.0001f)
    }
}
