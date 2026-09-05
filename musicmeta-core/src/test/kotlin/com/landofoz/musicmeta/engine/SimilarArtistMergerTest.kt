package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.provider.deezer.DeezerMapper
import com.landofoz.musicmeta.provider.deezer.DeezerRelatedArtist
import com.landofoz.musicmeta.provider.lastfm.LastFmMapper
import com.landofoz.musicmeta.provider.lastfm.LastFmSimilarArtist
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

    // --- grouping by identifier, not by name alone ---

    @Test
    fun `two same-name artists carrying different MBIDs stay two entries`() {
        // Given - one name held by two MusicBrainz artists, one entry from each of two providers
        val artists = listOf(
            SimilarArtist(
                name = "Loathe",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-uk"),
                matchScore = 0.6f,
                sources = listOf("listenbrainz"),
            ),
            SimilarArtist(
                name = "Loathe",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-malta"),
                matchScore = 0.4f,
                sources = listOf("lastfm"),
            ),
        )

        // When - merging the two entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - each act keeps its own entry, its own MBID and its own single source
        assertEquals(2, merged.size)
        assertEquals(listOf("mbid-uk", "mbid-malta"), merged.map { it.identifiers.musicBrainzId })
        assertEquals(listOf(listOf("listenbrainz"), listOf("lastfm")), merged.map { it.sources })
    }

    @Test
    fun `an artist with no MBID joins the same-name group and lends it its score`() {
        // Given - an identifier-less entry and a same-name entry carrying an MBID
        val artists = listOf(
            SimilarArtist(name = "Bad Omens", matchScore = 0.5f, sources = listOf("deezer")),
            SimilarArtist(
                name = "Bad Omens",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-metalcore"),
                matchScore = 0.5f,
                sources = listOf("listenbrainz"),
            ),
        )

        // When - merging the two entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - one entry carries both sources and the only MBID either offered
        assertEquals(1, merged.size)
        assertEquals(listOf("deezer", "listenbrainz"), merged.single().sources)
        assertEquals("mbid-metalcore", merged.single().identifiers.musicBrainzId)
    }

    @Test
    fun `two names sharing one MBID become one entry`() {
        // Given - two providers naming one MusicBrainz artist differently
        val artists = listOf(
            SimilarArtist(
                name = "Africa 70",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-africa70"),
                matchScore = 0.6f,
                sources = listOf("listenbrainz"),
            ),
            SimilarArtist(
                name = "Fela Kuti & Afrika 70",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-africa70"),
                matchScore = 0.4f,
                sources = listOf("lastfm"),
            ),
        )

        // When - merging the two entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - the identifier wins over the two names and the scores sum under the first name
        assertEquals(1, merged.size)
        assertEquals("Africa 70", merged.single().name)
        assertEquals(listOf("listenbrainz", "lastfm"), merged.single().sources)
    }

    @Test
    fun `an MBID-carrying artist refuses a same-name group already holding a different MBID`() {
        // Given - an identifier-less entry, then two same-name entries whose MBIDs disagree
        val artists = listOf(
            SimilarArtist(name = "Spiritbox", matchScore = 0.5f, sources = listOf("deezer")),
            SimilarArtist(
                name = "Spiritbox",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-canada"),
                matchScore = 0.4f,
                sources = listOf("listenbrainz"),
            ),
            SimilarArtist(
                name = "Spiritbox",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-netherlands"),
                matchScore = 0.3f,
                sources = listOf("lastfm"),
            ),
        )

        // When - merging the three entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - the first group takes the identifier-less entry and closes against the second MBID
        assertEquals(2, merged.size)
        assertEquals(listOf("mbid-canada", "mbid-netherlands"), merged.map { it.identifiers.musicBrainzId })
        assertEquals(listOf(listOf("deezer", "listenbrainz"), listOf("lastfm")), merged.map { it.sources })
    }

    @Test
    fun `an MBID-less artist joins the first same-name group rather than a bucket of its own`() {
        // Given - two same-name groups already open under different MBIDs, then two nameless-id entries
        val artists = listOf(
            SimilarArtist(
                name = "Sungazer",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-ny"),
                matchScore = 0.5f,
                sources = listOf("listenbrainz"),
            ),
            SimilarArtist(
                name = "Sungazer",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-colorado"),
                matchScore = 0.4f,
                sources = listOf("lastfm"),
            ),
            SimilarArtist(name = "Sungazer", matchScore = 0.3f, sources = listOf("deezer")),
            SimilarArtist(name = "Bicep", matchScore = 0.2f, sources = listOf("deezer")),
        )

        // When - merging the four entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - the unidentified Sungazer joins the first Sungazer, and Bicep is not swept in with it
        assertEquals(3, merged.size)
        val sungazers = merged.filter { it.name == "Sungazer" }
        assertEquals(listOf("mbid-ny", "mbid-colorado"), sungazers.map { it.identifiers.musicBrainzId })
        assertEquals(listOf("listenbrainz", "deezer"), sungazers.first().sources)
        assertEquals(listOf("deezer"), merged.single { it.name == "Bicep" }.sources)
    }

    @Test
    fun `the two Bad Omens the three providers return do not become one act`() {
        // Given - the Bad Omens rows the sleep-token capture holds, mapped by the providers' own mappers
        val deezer = DeezerMapper.toSimilarArtists(
            listOf(DeezerRelatedArtist(id = 9700940L, name = "Bad Omens")),
        ).artists
        val labs = ListenBrainzMapper.toSimilarArtists(
            listOf(
                ListenBrainzSimilarArtist(
                    artistMbid = "eecada09-acfc-472d-ae55-e9e5a43f12d8",
                    name = "Bad Omens",
                    score = 114,
                ),
            ),
        ).artists
        val lastfm = LastFmMapper.toSimilarArtists(
            listOf(
                LastFmSimilarArtist(
                    name = "Bad Omens",
                    matchScore = 0.900919f,
                    mbid = "8834d8b5-72a4-4a6e-9d35-3a041b8579fa",
                ),
            ),
        ).artists

        // When - merging them in the engine's registration order
        val merged = SimilarArtistMerger.mergeArtists(deezer + labs + lastfm)

        // Then - the metalcore band and the 1960s garage band stay apart, Deezer's row joining the first
        assertEquals(2, merged.size)
        assertEquals(
            listOf("eecada09-acfc-472d-ae55-e9e5a43f12d8", "8834d8b5-72a4-4a6e-9d35-3a041b8579fa"),
            merged.map { it.identifiers.musicBrainzId },
        )
        assertEquals(listOf(listOf("deezer", "listenbrainz"), listOf("lastfm")), merged.map { it.sources })
    }

    @Test
    fun `a blank MusicBrainz id groups nothing, so three names stay three entries`() {
        // Given - three different artists whose provider put a blank string where an MBID goes
        val artists = listOf(
            SimilarArtist(
                name = "Bicep",
                identifiers = EnrichmentIdentifiers(musicBrainzId = ""),
                matchScore = 0.6f,
                sources = listOf("custom"),
            ),
            SimilarArtist(
                name = "Overmono",
                identifiers = EnrichmentIdentifiers(musicBrainzId = ""),
                matchScore = 0.4f,
                sources = listOf("custom"),
            ),
            SimilarArtist(
                name = "Joy Orbison",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "   "),
                matchScore = 0.3f,
                sources = listOf("custom"),
            ),
        )

        // When - merging the three entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - a blank id is no id, so the names keep them apart instead of one blank-keyed group
        assertEquals(listOf("Bicep", "Overmono", "Joy Orbison"), merged.map { it.name })
    }

    @Test
    fun `one MusicBrainz id written in two cases is one act`() {
        // Given - two providers reporting one id, one of them upper-cased, under different names
        val artists = listOf(
            SimilarArtist(
                name = "Africa 70",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "dc45f2dc-ef36-4a7a-aa52-97495fca8ced"),
                matchScore = 0.6f,
                sources = listOf("listenbrainz"),
            ),
            SimilarArtist(
                name = "Fela Kuti & Afrika 70",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "DC45F2DC-EF36-4A7A-AA52-97495FCA8CED"),
                matchScore = 0.4f,
                sources = listOf("lastfm"),
            ),
        )

        // When - merging the two entries
        val merged = SimilarArtistMerger.mergeArtists(artists)

        // Then - the case of the id does not make a second act of it
        assertEquals(1, merged.size)
        assertEquals(listOf("listenbrainz", "lastfm"), merged.single().sources)
    }
}
