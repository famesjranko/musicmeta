package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.demo.ui.Terminal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The commands that reach the engine: `pin`, `invalidate --types`, and `stream`. */
class EngineCommandTest {

    private fun stateWith(engine: FakeEngine, term: Terminal): DemoState =
        DemoState(logger = DemoLogger(term)).also { it.engine = engine }

    private fun run(engine: FakeEngine, command: (DemoState, Terminal) -> Unit): String =
        captureOutput { term -> command(stateWith(engine, term), term) }

    // --- pin ---

    @Test
    fun `pin marks the named type for the named entity`() {
        // Given - an engine that records what it was asked to pin
        val engine = FakeEngine()

        // When - pinning one type on an artist
        run(engine) { state, term -> handlePin("artist Radiohead bio", state, term) }

        // Then - the engine was told, so the type survives a later automatic result
        assertEquals(
            listOf(EnrichmentRequest.forArtist("Radiohead") to EnrichmentType.ARTIST_BIO),
            engine.pinned,
        )
    }

    @Test
    fun `pin resolves the type against the kind of entity named`() {
        // Given - the one alias whose type depends on what was asked for
        val engine = FakeEngine()

        // When - pinning it on a track
        run(engine) { state, term -> handlePin("track Paranoid Android by Radiohead popularity", state, term) }

        // Then - the track's own popularity type is what got pinned
        assertEquals(
            listOf(
                EnrichmentRequest.forTrack("Paranoid Android", "Radiohead") to EnrichmentType.TRACK_POPULARITY,
            ),
            engine.pinned,
        )
    }

    @Test
    fun `pin with an unresolvable type pins nothing and names it`() {
        // Given - a typo where a type name belongs
        val engine = FakeEngine()

        // When - pinning it on an artist
        val output = run(engine) { state, term -> handlePin("artist Radiohead boi", state, term) }

        // Then - nothing is pinned and the name is reported
        assertTrue(engine.pinned.isEmpty())
        assertTrue(output, output.contains("boi"))
    }

    // --- invalidate ---

    @Test
    fun `invalidate without types clears every type at once`() {
        // Given - an engine that records what it was asked to invalidate
        val engine = FakeEngine()

        // When - invalidating an artist with no --types flag
        run(engine) { state, term -> handleInvalidate("artist Radiohead", state, term) }

        // Then - the all-types overload is what ran
        assertEquals(listOf(EnrichmentRequest.forArtist("Radiohead") to null), engine.invalidated)
    }

    @Test
    fun `invalidate honours the types flag it is given`() {
        // Given - an engine that records what it was asked to invalidate
        val engine = FakeEngine()

        // When - invalidating two named types on an artist
        run(engine) { state, term -> handleInvalidate("artist Radiohead --types bio,photo", state, term) }

        // Then - one call per named type, and no all-types call that would clear the rest
        assertEquals(
            setOf(
                EnrichmentRequest.forArtist("Radiohead") to EnrichmentType.ARTIST_BIO,
                EnrichmentRequest.forArtist("Radiohead") to EnrichmentType.ARTIST_PHOTO,
            ),
            engine.invalidated.toSet(),
        )
        assertEquals(2, engine.invalidated.size)
    }

    @Test
    fun `invalidate with an unresolvable type clears nothing`() {
        // Given - a --types flag carrying a typo
        val engine = FakeEngine()

        // When - invalidating with it
        val output = run(engine) { state, term -> handleInvalidate("artist Radiohead --types boi", state, term) }

        // Then - nothing is cleared, rather than everything
        assertTrue(engine.invalidated.isEmpty())
        assertTrue(output, output.contains("Unknown type(s): boi"))
    }

    // --- stream ---

    private fun bio(text: String) = EnrichmentResult.Success(
        type = EnrichmentType.ARTIST_BIO,
        data = EnrichmentData.Biography(text = text, source = "lastfm"),
        provider = "lastfm",
        confidence = 0.9f,
    )

    private fun snapshots(): List<EnrichmentResults> {
        val requested = setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO)
        return listOf(
            resultsOf(emptyMap(), requested),
            resultsOf(mapOf(EnrichmentType.ARTIST_BIO to bio("An English rock band.")), requested),
            resultsOf(
                mapOf(
                    EnrichmentType.ARTIST_BIO to bio("An English rock band."),
                    EnrichmentType.ARTIST_PHOTO to EnrichmentResult.NotFound(EnrichmentType.ARTIST_PHOTO, provider = "fanarttv"),
                ),
                requested,
            ),
        )
    }

    @Test
    fun `stream prints one row per type as it settles`() {
        // Given - a stream whose two types settle on separate emissions
        val engine = FakeEngine(snapshots())

        // When - streaming an artist for exactly those types
        val output = run(engine) { state, term ->
            executeStream("artist Radiohead --types bio,photo", state, term)
        }

        // Then - each settled type has its own row
        assertTrue(output, output.contains("Artist Bio"))
        assertTrue(output, output.contains("Artist Photo"))
    }

    @Test
    fun `stream prints a type once, not again on every later snapshot`() {
        // Given - a stream that repeats the settled bio in its terminal emission
        val engine = FakeEngine(snapshots())

        // When - streaming an artist for both types
        val output = run(engine) { state, term ->
            executeStream("artist Radiohead --types bio,photo", state, term)
        }

        // Then - the row that settled first is not reprinted, since a snapshot is cumulative
        assertEquals(1, output.split("Artist Bio").size - 1)
    }

    @Test
    fun `stream says which way a type failed rather than only that it did`() {
        // Given - a stream whose two types settle as a rate limit and an error
        val requested = setOf(EnrichmentType.ARTIST_BIO, EnrichmentType.ARTIST_PHOTO)
        val engine = FakeEngine(
            listOf(
                resultsOf(
                    mapOf(
                        EnrichmentType.ARTIST_BIO to EnrichmentResult.RateLimited(
                            EnrichmentType.ARTIST_BIO,
                            provider = "lastfm",
                        ),
                        EnrichmentType.ARTIST_PHOTO to EnrichmentResult.Error(
                            EnrichmentType.ARTIST_PHOTO,
                            provider = "fanarttv",
                            message = "connection reset",
                            errorKind = ErrorKind.NETWORK,
                        ),
                    ),
                    requested,
                ),
            ),
        )

        // When - streaming an artist for both types
        val output = run(engine) { state, term ->
            executeStream("artist Radiohead --types bio,photo", state, term)
        }

        // Then - each row names its own failure, and both still count as settled
        assertTrue(output, output.contains("rate limited"))
        assertTrue(output, output.contains("NETWORK"))
        assertTrue(output, output.contains("2/2 types settled"))
    }

    @Test
    fun `stream closes with what settled out of what was requested`() {
        // Given - a stream over two requested types
        val engine = FakeEngine(snapshots())

        // When - streaming an artist for both
        val output = run(engine) { state, term ->
            executeStream("artist Radiohead --types bio,photo", state, term)
        }

        // Then - the tally names both, so a type that never settled would be visible
        assertTrue(output, output.contains("2/2 types settled"))
    }
}
