package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kit's own coverage. Every later child inherits whatever this gets wrong, so a primitive that
 * lies is worse than no primitive: a silent empty pool, an engine that quietly reached the network,
 * or an identity rule that compares nothing all turn a whole child green while testing nothing.
 */
class TestKitTest {

    // --- P1 UpstreamPools ---

    @Test
    fun `a missing scenario fails with the path it looked for`() {
        // Given - a scenario name no pool directory exists for
        val scenario = "no-such-scenario"

        // When - loading it
        val thrown = assertThrows(IllegalStateException::class.java) { UpstreamPools.load(scenario) }

        // Then - the failure names the classpath path, rather than yielding an empty fake
        assertTrue(
            "message should name the missing path, was: ${thrown.message}",
            thrown.message!!.contains("/pools/$scenario/manifest.json"),
        )
    }

    @Test
    fun `a manifest naming a missing file fails with that file's path`() {
        // Given - a scenario whose manifest names a body file that is not present
        val scenario = "kit-missing-file-probe"

        // When - loading it
        val thrown = assertThrows(IllegalStateException::class.java) { UpstreamPools.load(scenario) }

        // Then - the failure names the missing body, not just the manifest
        assertTrue(
            "message should name the missing body file, was: ${thrown.message}",
            thrown.message!!.contains("/pools/$scenario/absent-body.json"),
        )
    }

    @Test
    fun `a pool loads its bodies under the fragments its manifest maps them to`() = runTest {
        // Given - the worked example's scenario
        val http = UpstreamPools.load("lrclib-first-result")

        // When - fetching a URL containing one of the mapped fragments
        val result = http.fetchJsonArrayResult("https://lrclib.net/api/search?track_name=Song")

        // Then - the mapped body answers, so the manifest wiring is real
        assertTrue("expected the pooled body, was $result", result is com.landofoz.musicmeta.http.HttpResult.Ok)
    }

    @Test
    fun `overlapping fragments are rejected rather than resolved by load order`() {
        // Given - a scenario whose two fragments are one a substring of the other
        val scenario = "kit-overlapping-fragments-probe"

        // When - loading it
        val thrown = assertThrows(IllegalStateException::class.java) { UpstreamPools.load(scenario) }

        // Then - the failure says which pair overlaps, instead of picking a winner per run
        assertTrue(
            "message should name the overlapping pair, was: ${thrown.message}",
            thrown.message!!.contains("fragments overlap"),
        )
    }

    // --- P2 TestStack ---

    @Test
    fun `every provider the stack builds holds the fake client`() = runTest {
        // Given - a stack built on a fake that has seen nothing
        val http = FakeHttpClient()
        val engine = TestStack.build(http)

        // When - enriching for a spread of types across many providers
        engine.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            setOf(EnrichmentType.ALBUM_ART, EnrichmentType.GENRE, EnrichmentType.ALBUM_METADATA),
        )

        // Then - the fake saw the traffic, so no provider was handed a real DefaultHttpClient
        assertTrue("the fake saw no requests, so the stack was not offline", http.requestedUrls.isNotEmpty())
        assertTrue(
            "every request must be an upstream URL the fake answered: ${http.requestedUrls.take(3)}",
            http.requestedUrls.all { it.startsWith("http") },
        )
    }

    @Test
    fun `ALL_KEYS registers every provider and a keyless build registers fewer`() = runTest {
        // Given - one stack with every key and one with none
        val keyed = TestStack.build(FakeHttpClient())
        val keyless = TestStack.build(FakeHttpClient(), keys = ApiKeyConfig())

        // When - counting registered providers off the engine rather than a literal
        val keyedCount = keyed.getProviders().size
        val keylessCount = keyless.getProviders().size

        // Then - the three key-gated providers are the whole difference
        assertEquals("a keyless build registers only the always-available providers", 9, keylessCount)
        assertEquals("ALL_KEYS must not leave a key-gated provider absent", keyedCount, keylessCount + 3)
    }

    // --- P4 EntityIdentity ---

    @Test
    fun `the identity rule accepts a differently written but equivalent qualifier`() {
        // Given - the same qualifier written with brackets on one side and a dash on the other
        // TitleMatcher's rule, from its own KDoc: a qualifier present on only one side is never
        // equivalent to its absence, but the two spellings of one qualifier are the same recording.
        val request = EnrichmentRequest.forTrack("Starman - 2012 Remaster", "David Bowie")
        val results = resultsWith(identity("Starman (2012 Remaster)", "David Bowie"))

        // When - the full rule runs
        // Then - it passes, because the production matcher normalizes the punctuation and not the text
        EntityIdentity.assertAnswersTheRequest(request, results)
    }

    @Test
    fun `the identity rule rejects a qualifier present on only one side`() {
        // Given - a resolution naming a live version of the requested studio track
        val request = EnrichmentRequest.forTrack("Starman", "David Bowie")
        val results = resultsWith(identity("Starman (Live)", "David Bowie"))

        // When - the full rule runs
        val thrown = assertThrows(AssertionError::class.java) {
            EntityIdentity.assertAnswersTheRequest(request, results)
        }

        // Then - it fails: a live take is a different recording, not decoration
        assertTrue("failure must name the title half, was: ${thrown.message}", thrown.message!!.contains("rejected=title"))
    }

    @Test
    fun `the identity rule rejects an unrelated title`() {
        // Given - a resolution naming a different song by the requested artist
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")
        val results = resultsWith(identity("Alabama Song", "David Bowie"))

        // When - the full rule runs
        val thrown = assertThrows(AssertionError::class.java) {
            EntityIdentity.assertAnswersTheRequest(request, results)
        }

        // Then - it fails, naming both halves and which one rejected it
        assertTrue(
            "failure must name what was requested and what came back, was: ${thrown.message}",
            thrown.message!!.contains("requested=track=Song") && thrown.message!!.contains("rejected=title"),
        )
    }

    @Test
    fun `the full rule refuses to run where a name search left nothing to compare`() {
        // Given - a RESOLVED resolution that named nothing, which is what a name search produces
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")
        val results = resultsWith(identity(title = null, artist = null))

        // When - the full rule runs
        val thrown = assertThrows(AssertionError::class.java) {
            EntityIdentity.assertAnswersTheRequest(request, results)
        }

        // Then - it fails loudly and names the method that is honest about the gap
        assertTrue(
            "failure must point at the weaker method, was: ${thrown.message}",
            thrown.message!!.contains("assertAnswersTheRequestWithoutCanonicalIdentity"),
        )
    }

    @Test
    fun `every non-Success variant is a decline and passes`() {
        // Given - a NotFound, a RateLimited and an Error, alongside a resolution that named nothing
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.LYRICS_PLAIN to EnrichmentResult.NotFound(EnrichmentType.LYRICS_PLAIN, "lrclib"),
                EnrichmentType.GENRE to EnrichmentResult.RateLimited(EnrichmentType.GENRE, "lastfm"),
                EnrichmentType.ALBUM_ART to EnrichmentResult.Error(EnrichmentType.ALBUM_ART, "caa", "boom"),
            ),
            requestedTypes = setOf(EnrichmentType.LYRICS_PLAIN, EnrichmentType.GENRE, EnrichmentType.ALBUM_ART),
            identity = identity(title = null, artist = null),
        )

        // When - the name-search rule runs
        // Then - all three pass; a decline is never an answer about the wrong entity
        EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
    }

    @Test
    fun `a matchScore on a status that cannot carry one is rejected`() {
        // Given - an UNRESOLVED resolution carrying a score, which its declaration forbids
        val request = EnrichmentRequest.forTrack("Song", "David Bowie")
        val results = resultsWith(
            IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.UNRESOLVED, matchScore = 1.0f),
        )

        // When - the name-search rule runs
        val thrown = assertThrows(AssertionError::class.java) {
            EntityIdentity.assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
        }

        // Then - the inconsistency is reported, which is what the weak mode is for
        assertTrue(
            "failure must name matchScore, was: ${thrown.message}",
            thrown.message!!.contains("matchScore"),
        )
    }

    // --- P5 Requests ---

    @Test
    fun `the call-cost rule names the URL that repeated`() = runTest {
        // Given - a fake asked for the same URL twice
        val http = FakeHttpClient()
        http.fetchJsonResult("https://example.test/a")
        http.fetchJsonResult("https://example.test/a")

        // When - the per-URL rule runs
        val thrown = assertThrows(AssertionError::class.java) { http.assertNoUrlRequestedTwice() }

        // Then - the message names the URL, not just a count
        assertTrue("failure must name the URL, was: ${thrown.message}", thrown.message!!.contains("example.test/a"))
    }

    // --- P6 eachProviderCapability ---

    @Test
    fun `enumeration covers a provider added at runtime without editing this test`() = runTest {
        // Given - the default stack plus one provider registered after it
        val extra = FakeProvider(
            id = "kit-extra",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_BOOKLET, 1)),
        )
        val engine = EnrichmentEngine.Builder()
            .httpClient(FakeHttpClient())
            .apiKeys(TestStack.ALL_KEYS)
            .withDefaultProviders()
            .addProvider(extra)
            .build()

        // When - enumerating every (provider, capability) pair
        val pairs = eachProviderCapability(engine)

        // Then - the runtime-added provider is there, because the list is read off the engine
        assertTrue(
            "enumeration must find a provider registered after the defaults",
            pairs.any { (info, _) -> info.id == "kit-extra" },
        )
        assertTrue(
            "enumeration must carry its declared capability too",
            pairs.any { (info, cap) -> info.id == "kit-extra" && cap.type == EnrichmentType.ALBUM_BOOKLET },
        )
    }

    private fun identity(title: String?, artist: String?) = IdentityResolution(
        identifiers = EnrichmentIdentifiers(),
        status = CanonicalStatus.RESOLVED,
        matchScore = 1.0f,
        title = title,
        artist = artist,
    )

    private fun resultsWith(identity: IdentityResolution) = EnrichmentResults(
        raw = mapOf(
            EnrichmentType.TRACK_METADATA to EnrichmentResult.Success(
                type = EnrichmentType.TRACK_METADATA,
                data = EnrichmentData.TrackMetadata(durationMs = 1000L),
                provider = "kit-test",
                confidence = 1.0f,
            ),
        ),
        requestedTypes = setOf(EnrichmentType.TRACK_METADATA),
        identity = identity,
    )
}
