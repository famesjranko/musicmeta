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
            .let { if (scenario.startsWith("plain request")) it.reversed() else it }
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
            arrayOf("credited recording in girls", "girls",
                EnrichmentRequest.forTrack("Girls Like You (feat. Cardi B)", "Maroon 5"), emptySet<Long>(), 1381582728L),
            arrayOf("credited recording in girls-album", "girls-album",
                EnrichmentRequest.forTrack("Girls Like You (feat. Cardi B)", "Maroon 5", "Red Pill Blues"), emptySet<Long>(), 1396134391L),
            arrayOf("credited recording in levitating", "levitating",
                EnrichmentRequest.forTrack("Levitating (feat. DaBaby)", "Dua Lipa"), emptySet<Long>(), 1532788352L),
            arrayOf("credited recording in cheap-thrills", "cheap-thrills",
                EnrichmentRequest.forTrack("Cheap Thrills (feat. Sean Paul)", "Sia"), emptySet<Long>(), 1082506273L),
            arrayOf("plain request in girls", "girls",
                EnrichmentRequest.forTrack("Girls Like You", "Maroon 5"), setOf(1396141911L, 1381582728L), 1396141911L),
            arrayOf("plain request in levitating", "levitating",
                EnrichmentRequest.forTrack("Levitating", "Dua Lipa"), setOf(1538003843L, 1532788352L), 1538003843L),
            arrayOf("plain request in cheap-thrills", "cheap-thrills",
                EnrichmentRequest.forTrack("Cheap Thrills", "Sia"), setOf(1055074640L, 1082506273L), 1055074640L),
            arrayOf("credited request prefers its recording over the plain album hint", "cheap-thrills",
                EnrichmentRequest.forTrack("Cheap Thrills (feat. Sean Paul)", "Sia", "This Is Acting"),
                emptySet<Long>(), 1082506273L),
            arrayOf("credited request cannot use a different guest or plain recording", "levitating",
                EnrichmentRequest.forTrack("Levitating (feat. Sukriti Kakar & Prakriti Kakar)", "Dua Lipa"),
                emptySet<Long>(), null),
            arrayOf("curly title apostrophes", "journey",
                EnrichmentRequest.forTrack("Don’t Stop Believin’", "Journey"), emptySet<Long>(), 160024096L),
            arrayOf("curly album apostrophe", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin'", "Journey", "Rock: The Train Kept a Rollin’"),
                emptySet<Long>(), 155679841L),
            arrayOf("curly artist apostrophe", "guns",
                EnrichmentRequest.forTrack("Sweet Child O' Mine", "Guns N’ Roses"), emptySet<Long>(), 1377813701L),
            arrayOf("catalogue feat credit", "umbrella",
                EnrichmentRequest.forTrack("Umbrella", "Rihanna"), emptySet<Long>(), 1441154437L),
            arrayOf("request ft credit prefers the credited recording", "girls",
                EnrichmentRequest.forTrack("Girls Like You (ft. Cardi B)", "Maroon 5"),
                emptySet<Long>(), 1381582728L),
            arrayOf("request featuring credit", "journey",
                EnrichmentRequest.forTrack("Don't Stop Believin' featuring Steve Perry", "Journey"),
                emptySet<Long>(), null),
            arrayOf("different credited artist", "umbrella",
                EnrichmentRequest.forTrack("Umbrella (feat. Kanye West)", "Rihanna"), setOf(1441154437L), null),
            arrayOf("normalized credited artist", "umbrella",
                EnrichmentRequest.forTrack("Umbrella (feat. JAY-Z)", "Rihanna"), setOf(1441154437L), 1441154437L),
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
