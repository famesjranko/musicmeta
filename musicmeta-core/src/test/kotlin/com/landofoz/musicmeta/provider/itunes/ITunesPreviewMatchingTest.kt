package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ITunesPreviewMatchingTest(
    private val scenario: String,
    private val fixtureName: String,
    private val request: EnrichmentRequest,
    private val includedIds: Set<Long>,
    private val expectedId: Long?,
) {
    @Test
    fun `preview matching preserves the requested recording`() = runTest {
        // Given - iTunes returns captured song rows for the requested recording.
        val http = FakeHttpClient()
        val captured = JSONObject(checkNotNull(javaClass.getResource("/fixtures/itunes-preview/$fixtureName.json")).readText())
            .getJSONArray("results")
        val rows = (0 until captured.length()).map { captured.getJSONObject(it) }
            .filter { includedIds.isEmpty() || it.getLong("trackId") in includedIds }
        val response = JSONObject().put("resultCount", rows.size).put("results", JSONArray(rows))
        http.givenJsonResponse("itunes.apple.com/search", response.toString())
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - requesting a preview with this title, artist and album spelling.
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - presentation differences preserve identity while covers and other versions are refused.
        if (expectedId == null) {
            assertTrue("$scenario returned $result", result is EnrichmentResult.NotFound)
        } else {
            assertTrue("$scenario returned $result", result is EnrichmentResult.Success)
            val success = result as EnrichmentResult.Success
            assertEquals(expectedId.toString(), success.resolvedIdentifiers?.extra?.get("itunesTrackId"))
            val expected = rows.single { it.getLong("trackId") == expectedId }
            assertEquals(expected.getString("previewUrl"), (success.data as EnrichmentData.TrackPreview).url)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("curly title apostrophes", "journey",
                EnrichmentRequest.forTrack("Don’t Stop Believin’", "Journey"), emptySet<Long>(), 160024096L),
            arrayOf("curly album apostrophe", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin'", "Journey", "Rock: The Train Kept a Rollin’"),
                emptySet<Long>(), 155679841L),
            arrayOf("curly artist apostrophe", "guns",
                EnrichmentRequest.forTrack("Sweet Child O' Mine", "Guns N’ Roses"), emptySet<Long>(), 1377813701L),
            arrayOf("catalogue feat credit", "umbrella",
                EnrichmentRequest.forTrack("Umbrella", "Rihanna"), emptySet<Long>(), 1441154437L),
            arrayOf("request ft credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' (ft. Steve Perry)", "Journey"),
                emptySet<Long>(), 160024096L),
            arrayOf("request featuring credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' featuring Steve Perry", "Journey"),
                emptySet<Long>(), 160024096L),
            arrayOf("featured remixes and other qualifiers", "umbrella",
                EnrichmentRequest.forTrack("Umbrella", "Rihanna"), setOf(1445276954L, 1442286685L, 1460177817L), null),
            arrayOf("wrong primary artist with a featured credit", "umbrella",
                EnrichmentRequest.forTrack("Umbrella (feat. JAY-Z)", "Rihanna"), setOf(1075626989L, 488249957L), null),
            arrayOf("live qualifier after credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' (ft. Steve Perry) (Live)", "Journey"),
                setOf(160024096L), null),
            arrayOf("acoustic qualifier before credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' (Acoustic) (featuring Steve Perry)", "Journey"),
                setOf(160024096L), null),
            arrayOf("karaoke qualifier after credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' (feat. Steve Perry) [Karaoke]", "Journey"),
                setOf(160024096L), null),
            arrayOf("other qualifier after credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' (ft. Steve Perry) [Instrumental]", "Journey"),
                setOf(160024096L), null),
            arrayOf("version inside the featured credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' (feat. Steve Perry - Live)", "Journey"),
                setOf(160024096L), null),
        )
    }
}
