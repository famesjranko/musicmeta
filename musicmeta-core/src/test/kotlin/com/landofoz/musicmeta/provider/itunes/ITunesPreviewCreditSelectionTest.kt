package com.landofoz.musicmeta.provider.itunes

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
class ITunesPreviewCreditSelectionTest(
    private val scenario: String,
    private val title: String,
    private val returnedTitles: List<String>,
    private val expectedIndex: Int?,
) {
    @Test
    fun `guest versions require enough evidence to choose a recording`() = runTest {
        // Given - a captured song row supplies boundary cases with controlled titles and distinct ids.
        val captured = JSONObject(checkNotNull(javaClass.getResource("/fixtures/itunes-preview/umbrella.json")).readText())
            .getJSONArray("results").getJSONObject(0)
        val rows = returnedTitles.mapIndexed { index, name ->
            JSONObject(captured.toString()).put("trackId", index + 1).put("trackName", name)
        }
        val http = FakeHttpClient()
        http.givenJsonResponse("itunes.apple.com/search",
            JSONObject().put("resultCount", rows.size).put("results", JSONArray(rows)).toString())
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - the caller asks for a plain or credited recording by the same primary artist.
        val result = provider.enrich(EnrichmentRequest.forTrack(title, captured.getString("artistName")),
            EnrichmentType.TRACK_PREVIEW)

        // Then - competing guests are refused unless the request identifies one recording.
        if (expectedIndex == null) {
            assertTrue("$scenario returned $result", result is EnrichmentResult.NotFound)
        } else {
            assertEquals("$scenario returned $result", (expectedIndex + 1).toString(),
                (result as? EnrichmentResult.Success)?.resolvedIdentifiers?.extra?.get("itunesTrackId"))
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("different guests", "Umbrella", listOf("Umbrella (feat. Y)", "Umbrella (feat. X)"), null),
            arrayOf("reversed guests", "Umbrella", listOf("Umbrella (feat. X)", "Umbrella (feat. Y)"), null),
            arrayOf("same normalized guests", "Umbrella", listOf("Umbrella (feat. JAŸ-Z)", "Umbrella (ft. JAY-Z)"), 0),
            arrayOf("plain row wins", "Umbrella", listOf("Umbrella (feat. Y)", "Umbrella (feat. X)", "Umbrella"), 2),
            arrayOf("named guest wins", "Umbrella (ft. X)", listOf("Umbrella (feat. Y)", "Umbrella (feat. X)"), 1),
        )
    }
}
