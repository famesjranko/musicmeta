package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The batched `arid:` search behind a similar artist's disambiguation.
 *
 * The body it is asserted against is the live capture the design was chosen on
 * (`pools/similar-artist-disambiguation/scenario.md`), taken 2026-09-06 for the pre-registered A/B —
 * it predates this code rather than being written to agree with it.
 *
 * Two properties matter more than the mapping: that four ids cost **one** request, which is the
 * entire argument for the batch over a lookup each, and that a failure is an empty answer rather
 * than an exception, because this runs on a result that has already been merged.
 */
class MusicBrainzArtistDisambiguationTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0L))
    }

    private fun givenTheCapturedBatch() {
        httpClient.givenJsonResponse(
            "artist?query=arid",
            UpstreamPools.body("similar-artist-disambiguation", "musicbrainz-arid-batch.json"),
        )
    }

    @Test
    fun `four ids are one request, and each id gets its own text`() = runTest {
        // Given - the live answer to a four-id arid search
        givenTheCapturedBatch()

        // When - all four are asked about at once
        val texts = provider.describeArtists(listOf(LOATHE_MT, SPIRITBOX_NL, SUNGAZER_CO, BAD_OMENS_MN))

        // Then - one request carried every id, and every id wears its own act's description
        assertEquals(1, httpClient.requestedUrls.size)
        val url = httpClient.requestedUrls.single()
        listOf(LOATHE_MT, SPIRITBOX_NL, SUNGAZER_CO, BAD_OMENS_MN).forEach {
            assertTrue("the query must name $it, got $url", url.contains(it))
        }
        assertEquals("Maltese death metal band", texts[LOATHE_MT])
        assertEquals("Dutch post-rock", texts[SPIRITBOX_NL])
        assertEquals("Colorado-based psychadelic, ambient, noise", texts[SUNGAZER_CO])
        assertEquals("60s garage rock band from Minnesota", texts[BAD_OMENS_MN])
    }

    @Test
    fun `an id the answer does not name is absent rather than given another row's text`() = runTest {
        // Given - the same four-artist answer, asked a fifth id MusicBrainz holds nothing for
        givenTheCapturedBatch()

        // When - the fifth is asked about alongside them
        val texts = provider.describeArtists(listOf(LOATHE_MT, RETIRED_MBID))

        // Then - the missing id is simply missing: a short answer never gets filled by position
        assertEquals("Maltese death metal band", texts[LOATHE_MT])
        assertNull(texts[RETIRED_MBID])
    }

    @Test
    fun `a failing search is an empty answer, not an exception`() = runTest {
        // Given - a route that fails the way a shed or unreachable MusicBrainz does
        httpClient.givenError("artist?query=arid")

        // When - the batch is asked for
        val texts = provider.describeArtists(listOf(LOATHE_MT, SPIRITBOX_NL))

        // Then - nothing comes back and nothing is thrown: this labels an answer that already merged
        assertEquals(emptyMap<String, String>(), texts)
    }

    @Test
    fun `asking nothing asks upstream nothing`() = runTest {
        // Given - a route that would answer if it were called
        givenTheCapturedBatch()

        // When - an empty id list is asked about
        val texts = provider.describeArtists(emptyList())

        // Then - no request at all, which is what every call with no same-name pair costs
        assertEquals(emptyMap<String, String>(), texts)
        assertEquals(0, httpClient.requestedUrls.size)
    }

    @Test
    fun `two readers in one call share the one answer`() = runTest {
        // Given - the live answer, and one call scope such as enrich() installs
        givenTheCapturedBatch()

        // When - the same ids are asked about twice inside that scope
        withContext(ProviderCallScope()) {
            provider.describeArtists(listOf(LOATHE_MT, SPIRITBOX_NL))
            provider.describeArtists(listOf(LOATHE_MT, SPIRITBOX_NL))
        }

        // Then - one request between them
        assertEquals(1, httpClient.requestedUrls.size)
    }

    @Test
    fun `a failed batch is not re-asked for the rest of the call`() = runTest {
        // Given - a failing route, and one call scope
        httpClient.givenError("artist?query=arid")

        // When - two readers ask for the same ids inside it
        withContext(ProviderCallScope()) {
            provider.describeArtists(listOf(LOATHE_MT, SPIRITBOX_NL))
            provider.describeArtists(listOf(LOATHE_MT, SPIRITBOX_NL))
        }

        // Then - still one request. A memo that held only successes would charge the failing endpoint
        // once per reader, which is the cost `docs/pitfalls.md` §23 records
        assertEquals(1, httpClient.requestedUrls.size)
    }

    private companion object {
        const val LOATHE_MT = "e9ea0fbc-ccc7-4e98-9290-0a41aa848fa2"
        const val SPIRITBOX_NL = "a39ad456-a697-4f32-aa36-c107f654d318"
        const val SUNGAZER_CO = "21006fdb-2e22-4950-91ed-8575a1a96b58"
        const val BAD_OMENS_MN = "8834d8b5-72a4-4a6e-9d35-3a041b8579fa"

        /** An id MusicBrainz answers nothing for — a merged or removed artist. */
        const val RETIRED_MBID = "00000000-0000-0000-0000-00000000dead"
    }
}
