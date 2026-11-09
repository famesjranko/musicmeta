package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder

@RunWith(Parameterized::class)
class MusicBrainzFeaturedCreditSyntaxTest(
    private val endpoint: String,
    private val mode: String,
    private val artist: String,
) {
    private val client = FakeHttpClient()
    private val api = MusicBrainzApi(client, RateLimiter(0))

    private fun queries() = client.requestedUrls.map {
        URLDecoder.decode(it.substringAfter("query=").substringBefore("&"), "UTF-8")
    }

    private fun suffix(strict: Boolean) = when (artist) {
        "Eminem feat." -> if (strict) "artistname:\"Eminem feat.\"" else "artistname:eminem~ AND artistname:feat~"
        "Featuring Rihanna" -> if (strict) "artistname:\"Featuring Rihanna\"" else "artistname:featuring~ AND artistname:rihanna~"
        "Eminem feat. ft. Rihanna" -> if (strict) "artistname:\"Eminem feat. ft. Rihanna\"" else "artistname:eminem~ AND artistname:feat~ AND artistname:ft AND artistname:rihanna~"
        "Eminemfeat. Rihanna" -> if (strict) "artistname:\"Eminemfeat. Rihanna\"" else "artistname:eminemfeat~ AND artistname:rihanna~"
        "!!! feat. Rihanna" -> if (strict) """artistname:"\!\!\!" AND artistname:"Rihanna"""" else """artistname:"\!\!\!" AND artistname:rihanna~"""
        else -> error("unknown syntax row: " + artist)
    }

    @Test
    fun `only one complete whitespace marker splits the artist`() = runTest {
        // Given - no response route, because this test pins syntax rather than upstream recall

        // When - searching the selected strict or fuzzy syntax row
        if (mode == "strict") {
            if (endpoint == "recording") api.searchRecordings("Bilie Jean", artist, limit = 3)
            else api.searchReleases("Bilie Jean", artist, limit = 3)
        } else if (endpoint == "recording") {
            api.searchRecordingsFuzzy("Bilie Jean", artist, limit = 3)
        } else {
            api.searchReleasesFuzzy("Bilie Jean", artist, limit = 3)
        }

        // Then - the literal expected query is sent once and returns no routed rows
        val field = endpoint
        val title = if (mode == "strict") field + ":\"Bilie Jean\"" else field + ":bilie~ AND " + field + ":jean~"
        assertEquals(listOf(title + " AND " + suffix(mode == "strict")), queries())
        assertEquals(1, client.requestedUrls.size)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}/{2}")
        fun cases() = listOf("recording", "release").flatMap { endpoint ->
            listOf("strict", "fuzzy").flatMap { mode ->
                listOf(
                    "Eminem feat.", "Featuring Rihanna", "Eminem feat. ft. Rihanna",
                    "Eminemfeat. Rihanna", "!!! feat. Rihanna",
                ).map { arrayOf(endpoint, mode, it) }
            }
        }
    }
}
