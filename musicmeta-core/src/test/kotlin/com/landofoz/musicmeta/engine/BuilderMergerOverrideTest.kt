package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.TopTrack
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression cover for #49: a caller's `addMerger` must win for every built-in merger type. */
class BuilderMergerOverrideTest {

    private class StubMerger(override val type: EnrichmentType) : ResultMerger {
        override fun merge(results: List<EnrichmentResult.Success>) =
            EnrichmentResult.Success(type, EnrichmentData.Metadata(), "caller_merger", 1.0f)
    }

    private val builtInMergerTypes = listOf(
        EnrichmentType.GENRE,
        EnrichmentType.SIMILAR_ARTISTS,
        EnrichmentType.SIMILAR_TRACKS,
        EnrichmentType.ARTIST_PHOTO,
        EnrichmentType.ALBUM_ART,
        EnrichmentType.ARTIST_TOP_TRACKS,
    )

    private val request = EnrichmentRequest.forArtist("Radiohead")

    @Test fun `addMerger overrides the built-in merger for every built-in type including ARTIST_TOP_TRACKS`() = runTest {
        // Given -- one engine with a caller merger registered for all six built-in merger types
        val builder = EnrichmentEngine.Builder()
        builtInMergerTypes.forEach { builder.addMerger(StubMerger(it)) }
        val engine = builder.build()

        // When -- enriching all six at once
        val results = engine.enrich(request, builtInMergerTypes.toSet())

        // Then -- the caller's merger ran for each; a built-in winning anywhere fails here
        val providers = builtInMergerTypes.associateWith {
            (results.raw[it] as? EnrichmentResult.Success)?.provider
        }
        assertEquals(builtInMergerTypes.associateWith { "caller_merger" }, providers)
    }

    @Test fun `ARTIST_TOP_TRACKS still resolves through the built-in merger with no override`() = runTest {
        // Given -- a provider returning top tracks and no caller merger
        val provider = FakeProvider(
            id = "p",
            capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_TOP_TRACKS, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.ARTIST_TOP_TRACKS,
                EnrichmentResult.Success(
                    EnrichmentType.ARTIST_TOP_TRACKS,
                    EnrichmentData.TopTracks(listOf(TopTrack(title = "Creep", artist = "Radiohead", rank = 1))),
                    "p",
                    0.9f,
                ),
            )
        }
        val engine = EnrichmentEngine.Builder().addProvider(provider).build()

        // When -- enriching top tracks
        val results = engine.enrich(request, setOf(EnrichmentType.ARTIST_TOP_TRACKS))

        // Then -- TopTrackMerger produced the result
        val success = results.raw[EnrichmentType.ARTIST_TOP_TRACKS] as EnrichmentResult.Success
        assertEquals("top_track_merger", success.provider)
    }
}
