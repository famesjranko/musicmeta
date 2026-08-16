package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.SimilarTrack
import com.landofoz.musicmeta.TopTrack
import com.landofoz.musicmeta.engine.ArtworkMerger
import com.landofoz.musicmeta.engine.DEFAULT_MERGERS
import com.landofoz.musicmeta.engine.DEFAULT_SYNTHESIZERS
import com.landofoz.musicmeta.engine.ResultMerger
import com.landofoz.musicmeta.engine.weakestProvenance
import com.landofoz.musicmeta.testkit.ContractSuite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The rule every registered [ResultMerger], and the two registered synthesizers, owe their callers:
 * a merged or synthesized result reports the **weakest** contributor's [LookupProvenance], read from
 * [weakestProvenance] itself rather than a strength list restated here — a test-local ordering would
 * pass exactly when this file agrees with the product, which proves nothing about the product.
 *
 * [subject] is [DEFAULT_MERGERS], the set `EnrichmentEngine.Builder` registers when a caller adds
 * none, read from there rather than hand-listed — a ninth merger appended to that list is covered by
 * every test below without editing this file.
 *
 * **Two structural limits, stated so this contract's coverage is not overstated:**
 * - `EnrichmentEngine.Builder.addMerger`/`.addSynthesizer` are public, so a consumer can register a
 *   merger this contract never runs against. It covers the built-in set, which is all it can cover.
 * - This file needs a payload fixture per [EnrichmentType] in [payloadFor]. A ninth merger registered
 *   on a type with no fixture here fails inside [payloadFor], not inside an assertion — still red
 *   without editing a test, but the failure says "add a fixture", not "your merger drops provenance".
 *
 * The two synthesizers are not folded into the enumerated loop: [GenreAffinityMatcher] derives from
 * exactly one contributor and [TimelineSynthesizer] diverges from every merger's own null handling
 * (see the dedicated tests below for both), so a single shared assertion would either hide one
 * synthesizer's real behaviour or force it to match a rule it does not follow.
 */
class MergerProvenanceContract : ContractSuite<List<ResultMerger>>() {

    override fun subject(): List<ResultMerger> = DEFAULT_MERGERS

    /** One usable payload per [EnrichmentType] a registered merger handles. */
    private fun payloadFor(type: EnrichmentType, seed: String): EnrichmentData = when (type) {
        EnrichmentType.GENRE -> EnrichmentData.Metadata(
            genreTags = listOf(GenreTag(name = seed, confidence = 0.8f)),
        )
        EnrichmentType.SIMILAR_ARTISTS -> EnrichmentData.SimilarArtists(
            artists = listOf(SimilarArtist(name = seed, matchScore = 0.8f)),
        )
        EnrichmentType.SIMILAR_TRACKS -> EnrichmentData.SimilarTracks(
            tracks = listOf(SimilarTrack(title = seed, artist = "artist", matchScore = 0.8f)),
        )
        EnrichmentType.ARTIST_PHOTO, EnrichmentType.ALBUM_ART ->
            EnrichmentData.Artwork(url = "https://example.test/$seed.jpg")
        EnrichmentType.ARTIST_TOP_TRACKS -> EnrichmentData.TopTracks(
            tracks = listOf(TopTrack(title = seed, artist = "artist", rank = 1)),
        )
        EnrichmentType.ARTIST_POPULARITY, EnrichmentType.TRACK_POPULARITY ->
            EnrichmentData.Popularity(listenCount = 10)
        else -> error(
            "MergerProvenanceContract has no payload fixture for $type; add one to payloadFor " +
                "before a merger registered on it can be trusted by this contract",
        )
    }

    private fun contribution(merger: ResultMerger, provider: String, provenance: LookupProvenance?) =
        EnrichmentResult.Success(
            type = merger.type,
            data = payloadFor(merger.type, provider),
            provider = provider,
            confidence = 0.9f,
            provenance = provenance,
        )

    private fun mergedProvenance(merger: ResultMerger, vararg provenances: LookupProvenance?): LookupProvenance? {
        val results = provenances.mapIndexed { index, provenance -> contribution(merger, "provider$index", provenance) }
        val merged = merger.merge(results)
        check(merged is EnrichmentResult.Success) {
            "${merger::class.simpleName}(${merger.type}) did not merge $results into a Success"
        }
        return merged.provenance
    }

    @Test
    fun `the registered merger set this contract walks is not empty`() {
        // Given - the registered set every enumerated rule below filters over
        val registered = subject()

        // When - that set is counted rather than inspected
        val count = registered.size

        // Then - it holds at least one merger, so an emptied list fails here instead of passing every
        // enumerated rule vacuously; those rules assert "no offender", which an empty set satisfies
        assertNotEquals("the registered merger set is empty, so every enumerated rule below is vacuous", 0, count)
    }

    @Test
    fun `every registered merger reports the weakest contributor's provenance`() {
        // Given - every registered merger, each fed one strong and one weak contributor, in both orders
        val strong = LookupProvenance.CANONICAL_ID
        val weak = LookupProvenance.FUZZY_NAME

        // When - each merger's result is compared against weakestProvenance itself for both orderings
        val offenders = subject().filterNot { merger ->
            mergedProvenance(merger, strong, weak) == weakestProvenance(listOf(strong, weak)) &&
                mergedProvenance(merger, weak, strong) == weakestProvenance(listOf(weak, strong))
        }

        // Then - none of the registered mergers reports a stronger route than its weakest contributor
        assertEquals(
            "mergers reporting a stronger route than their weakest contributor",
            emptyList<String>(),
            offenders.map { "${it::class.simpleName}(${it.type})" },
        )
    }

    @Test
    fun `every registered merger treats a null contributor as the weakest`() {
        // Given - every registered merger, each fed a canonical-id contributor and a contributor that reported no route
        // When - each merger merges that pair
        val offenders = subject().filterNot { merger ->
            mergedProvenance(merger, LookupProvenance.CANONICAL_ID, null) == LookupProvenance.FUZZY_NAME
        }

        // Then - the null contributor is coerced to FUZZY_NAME by every merger, never ignored in favour of the known route
        assertEquals(
            "mergers ignoring a null contributor instead of coercing it to FUZZY_NAME",
            emptyList<String>(),
            offenders.map { "${it::class.simpleName}(${it.type})" },
        )
    }

    @Test
    fun `every registered merger passes a single contributor's provenance through unchanged`() {
        // Given - every registered merger, each fed exactly one contributor
        val only = LookupProvenance.QUALIFIER_FALLBACK_NAME

        // When - each merger merges that single result
        val offenders = subject().filterNot { mergedProvenance(it, only) == only }

        // Then - the lone contributor's route survives every merger unaltered
        assertEquals(
            "mergers altering a single contributor's provenance",
            emptyList<String>(),
            offenders.map { "${it::class.simpleName}(${it.type})" },
        )
    }

    @Test
    fun `weakestProvenance agrees with LookupProvenance's declaration order`() {
        // Given - every adjacent pair of LookupProvenance's declared values, strongest first
        val declared = LookupProvenance.entries

        // When - weakestProvenance is asked to pick between each adjacent pair
        val disagreements = declared.zipWithNext().filterNot { (stronger, weaker) ->
            weakestProvenance(listOf(stronger, weaker)) == weaker
        }

        // Then - weakestProvenance names the later-declared value every time, so the two orderings cannot silently drift apart
        assertEquals(
            "adjacent LookupProvenance pairs where weakestProvenance disagrees with declaration order",
            emptyList<Pair<LookupProvenance, LookupProvenance>>(),
            disagreements,
        )
    }

    @Test
    fun `a payload-free merge reports the strongest contributor, a known gap owned by a separate fix`() {
        // Given - every registered merger except ArtworkMerger, which returns NotFound rather than taking this path, each fed two contributors whose payload carries nothing that merger's own filter treats as usable
        val strong = LookupProvenance.CANONICAL_ID
        val weak = LookupProvenance.FUZZY_NAME
        val mergersWithTheHole = subject().filterNot { it is ArtworkMerger }

        // When - each merges a strong-then-weak pair, both carrying a payload of a type the merger's own contributor filter excludes entirely
        val reported = mergersWithTheHole.associateWith { merger ->
            val unusablePayload = EnrichmentData.Artwork(url = "https://example.test/no-usable-payload.jpg")
            val first = EnrichmentResult.Success(merger.type, unusablePayload, "p0", 0.9f, provenance = strong)
            val second = EnrichmentResult.Success(merger.type, unusablePayload, "p1", 0.9f, provenance = weak)
            val merged = merger.merge(listOf(first, second))
            check(merged is EnrichmentResult.Success) { "${merger::class.simpleName}(${merger.type}) did not merge" }
            merged.provenance
        }

        // Then - every one of them reports the first contributor's stronger route rather than the weakest, the exact inversion this contract otherwise guards against; closing it is a separate, already-filed fix, so this test pins the gap rather than hiding it
        reported.forEach { (merger, provenance) ->
            assertEquals("${merger::class.simpleName}(${merger.type}) payload-free provenance", strong, provenance)
        }
    }

    @Test
    fun `GenreAffinityMatcher passes its single GENRE contributor's route through, a pass-through not an exemption`() {
        // Given - a GENRE result whose route is a fuzzy name match, and the registered GENRE_DISCOVERY synthesizer
        val genre = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = listOf(GenreTag(name = "rock", confidence = 0.9f))),
            provider = "genre_merger",
            confidence = 0.9f,
            provenance = LookupProvenance.FUZZY_NAME,
        )
        val genreAffinityMatcher = DEFAULT_SYNTHESIZERS.single { it.type == EnrichmentType.GENRE_DISCOVERY }

        // When - the synthesizer derives GENRE_DISCOVERY from that single GENRE contributor
        val derived = genreAffinityMatcher.synthesize(
            mapOf(EnrichmentType.GENRE to genre),
            null,
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "Bowie"),
        )

        // Then - the derived result carries the one contributor's route unchanged, because the weakest of a single-element set is that element
        assertNotNull(derived)
        assertEquals(LookupProvenance.FUZZY_NAME, (derived as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `TimelineSynthesizer drops a null-provenance dependency instead of coercing it to FUZZY_NAME`() {
        // Given - the registered ARTIST_TIMELINE synthesizer, a discography contributor with no recorded route, and a band-members contributor with a canonical one
        val timelineSynthesizer = DEFAULT_SYNTHESIZERS.single { it.type == EnrichmentType.ARTIST_TIMELINE }
        val discography = EnrichmentResult.Success(
            type = EnrichmentType.ARTIST_DISCOGRAPHY,
            data = EnrichmentData.Discography(albums = emptyList()),
            provider = "discography_provider",
            confidence = 0.9f,
            provenance = null,
        )
        val bandMembers = EnrichmentResult.Success(
            type = EnrichmentType.BAND_MEMBERS,
            data = EnrichmentData.BandMembers(members = emptyList()),
            provider = "band_members_provider",
            confidence = 0.9f,
            provenance = LookupProvenance.CANONICAL_ID,
        )

        // When - the synthesizer combines both dependencies
        val synthesized = timelineSynthesizer.synthesize(
            mapOf(EnrichmentType.ARTIST_DISCOGRAPHY to discography, EnrichmentType.BAND_MEMBERS to bandMembers),
            null,
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "Bowie"),
        )

        // Then - the null-provenance dependency is dropped rather than coerced to FUZZY_NAME as every merger would, so the summary reports the remaining contributor's route unweakened — a divergence this contract records rather than fixes
        assertEquals(LookupProvenance.CANONICAL_ID, (synthesized as EnrichmentResult.Success).provenance)
    }
}
