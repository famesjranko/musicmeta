package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The warm-up's two obligations: every entity the landing page suggests is warmed, and readiness
 * is decided by the first of them alone. A visitor's first click lands on a suggestion, so an
 * unwarmed suggestion pays a full fan-out; a readiness that waited for all of them would multiply
 * cold start and risk the hosting platform's startup probe.
 */
class WarmUpTest {

    /** Records every request the engine asks it to enrich, so a test can see what was warmed. */
    private class RecordingGenreProvider : EnrichmentProvider {
        val recordedRequests = ConcurrentLinkedQueue<EnrichmentRequest>()
        override val id = "stub-genre"
        override val displayName = "Stub Genre"
        override val requiresApiKey = false
        override val isAvailable = true
        override val capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
            recordedRequests.add(request)
            return EnrichmentResult.Success(type, EnrichmentData.Metadata(genres = listOf("rock")), id, 0.9f)
        }
    }

    private fun engineWith(provider: EnrichmentProvider): EnrichmentEngine =
        EnrichmentEngine.Builder().addProvider(provider).cache(InMemoryEnrichmentCache()).build()

    private val shippedPage: String =
        checkNotNull(WarmUpTest::class.java.getResourceAsStream("/index.html")) { "index.html missing" }
            .readBytes()
            .decodeToString()

    private val artist = ShowcaseQuery("artist", "Radiohead", artist = "", album = null)
    private val album = ShowcaseQuery("album", "OK Computer", artist = "Radiohead", album = null)
    private val track = ShowcaseQuery("track", "Karma Police", artist = "Radiohead", album = "OK Computer")

    /** An MBID lookup needs a MusicBrainz identity provider, so on a stub engine it throws. */
    private val unresolvableMbid = ShowcaseQuery("mbid", "0383dadf-2a4e-4d10-a46a-e9e041da8eb3", "", null)

    @Test
    fun `every suggested query on the shipped landing page is a showcase entity`() {
        // Given - the index.html the server actually serves
        val html = shippedPage

        // When - reading the suggested queries out of it
        val showcase = parseShowcase(html)

        // Then - all four suggestions are there, each carrying the fields its kind needs
        assertEquals(listOf("artist", "album", "track", "mbid"), showcase.map { it.kind })
        assertEquals("Radiohead", showcase.first { it.kind == "artist" }.name)
        assertEquals("Radiohead", showcase.first { it.kind == "album" }.artist)
        assertEquals("OK Computer", showcase.first { it.kind == "track" }.album)
        assertNotNull(showcase.first { it.kind == "mbid" }.name)
    }

    @Test
    fun `a button outside the suggestions block is not a showcase entity`() {
        // Given - a page whose other buttons carry the same data attributes
        val html = """
            <button data-kind="artist" data-name="Elsewhere">nav</button>
            <div id="examples">Try: <button data-kind="artist" data-name="Radiohead">Radiohead</button></div>
            <button data-kind="track" data-name="Footer">footer</button>
        """.trimIndent()

        // When - reading the suggested queries out of it
        val showcase = parseShowcase(html)

        // Then - only the button inside the suggestions block counts
        assertEquals(listOf("Radiohead"), showcase.map { it.name })
    }

    @Test
    fun `a suggestion of an unknown kind is not a showcase entity`() {
        // Given - a suggestions block holding a kind no enrichment endpoint accepts
        val html = """<div id="examples"><button data-kind="playlist" data-name="Mixtape">m</button></div>"""

        // When - reading the suggested queries out of it
        val showcase = parseShowcase(html)

        // Then - nothing is warmed for it
        assertTrue(showcase.isEmpty())
    }

    @Test
    fun `warm-up warms every showcase entity, not only the first`() {
        // Given - an engine recording what it is asked to enrich, and three suggestions
        val provider = RecordingGenreProvider()
        val engine = engineWith(provider)

        // When - warming the whole showcase
        runBlocking { warmShowcase(engine, listOf(artist, album, track)) {} }

        // Then - the album and track were fetched as well as the artist
        val recorded = provider.recordedRequests.toList()
        assertTrue(recorded.any { it is EnrichmentRequest.ForArtist && it.name == "Radiohead" })
        assertTrue(recorded.any { it is EnrichmentRequest.ForAlbum && it.title == "OK Computer" })
        assertTrue(recorded.any { it is EnrichmentRequest.ForTrack && it.title == "Karma Police" })
    }

    @Test
    fun `warm-up reaches its readiness verdict on the first showcase entity alone`() {
        // Given - an engine that answers, and three suggestions to warm
        val engine = engineWith(RecordingGenreProvider())
        val verdicts = mutableListOf<HealthState>()

        // When - warming the whole showcase
        runBlocking { warmShowcase(engine, listOf(artist, album, track)) { verdicts.add(it) } }

        // Then - exactly one verdict was published, and the entities after the first did not add one
        assertEquals(listOf(HealthState.READY), verdicts)
    }

    @Test
    fun `a later showcase entity that fails leaves the verdict and the rest of the warm-up alone`() {
        // Given - a showcase whose second entity cannot be looked up on this engine
        val provider = RecordingGenreProvider()
        val engine = engineWith(provider)
        val verdicts = mutableListOf<HealthState>()

        // When - warming it with the failing entity between two that succeed
        runBlocking { warmShowcase(engine, listOf(artist, unresolvableMbid, track)) { verdicts.add(it) } }

        // Then - the first entity's verdict stands and the entity after the failure was still warmed
        assertEquals(listOf(HealthState.READY), verdicts)
        assertTrue(provider.recordedRequests.any { it is EnrichmentRequest.ForTrack && it.title == "Karma Police" })
    }

    @Test
    fun `a first showcase entity that fails is the verdict, and the rest still warm`() {
        // Given - a showcase whose first entity cannot be looked up on this engine
        val provider = RecordingGenreProvider()
        val engine = engineWith(provider)
        val verdicts = mutableListOf<HealthState>()

        // When - warming it with the failing entity first
        runBlocking { warmShowcase(engine, listOf(unresolvableMbid, artist)) { verdicts.add(it) } }

        // Then - readiness is DEGRADED rather than stuck at WARMING, and the artist was still warmed
        assertEquals(listOf(HealthState.DEGRADED), verdicts)
        assertTrue(provider.recordedRequests.any { it is EnrichmentRequest.ForArtist && it.name == "Radiohead" })
    }

    @Test
    fun `an empty showcase still reaches a verdict rather than staying WARMING`() {
        // Given - a page that offers no suggested queries at all
        val engine = engineWith(RecordingGenreProvider())
        val verdicts = mutableListOf<HealthState>()

        // When - warming that empty showcase
        runBlocking { warmShowcase(engine, emptyList()) { verdicts.add(it) } }

        // Then - a verdict is published, and it claims nothing that was never proved
        assertEquals(listOf(HealthState.DEGRADED), verdicts)
    }
}
