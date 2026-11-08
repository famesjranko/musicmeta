package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ITunesPreviewAlbumTest(
    private val fixture: String,
    private val title: String,
    private val artist: String,
    private val album: String,
    private val expectedId: String,
) {
    @Test
    fun `album hints prefer compatible editions without rejecting other matching recordings`() = runTest {
        // Given - Apple returns an unchanged captured pool with several releases or editions.
        val http = FakeHttpClient()
        http.givenJsonResponse("itunes.apple.com/search",
            checkNotNull(javaClass.getResource("/fixtures/itunes-preview/$fixture.json")).readText())
        val provider = ITunesProvider(http, RateLimiter(0))

        // When - the caller supplies an album hint alongside the recording title and artist.
        val result = provider.enrich(EnrichmentRequest.forTrack(title, artist, album), EnrichmentType.TRACK_PREVIEW)

        // Then - the compatible album wins when available and a matching recording otherwise answers in one search.
        assertEquals(expectedId, (result as? EnrichmentResult.Success)?.resolvedIdentifiers?.extra?.get("itunesTrackId"))
        assertEquals(1, http.requestedUrls.size)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}: {1} / {3}")
        fun cases(): List<Array<String>> = listOf(
            arrayOf("journey-escape", "Don't Stop Believin'", "Journey", "Escape", "160024096"),
            arrayOf("journey-escape", "Don't Stop Believin' (2022 Remaster)", "Journey", "Escape", "1640102584"),
            arrayOf("umbrella", "Umbrella", "Rihanna", "Good Girl Gone Bad", "1441154437"),
            arrayOf("journey", "Don't Stop Believin'", "Journey",
                "Rock: The Train Kept a Rollin' (Deluxe Edition)", "155679841"),
            arrayOf("journey", "Don't Stop Believin'", "Journey",
                "Rock: The Train Kept a Rollin' [Expanded Edition]", "155679841"),
            arrayOf("journey", "Don't Stop Believin'", "Journey",
                "Rock: The Train Kept a Rollin' - Anniversary Edition", "155679841"),
        )
    }
}
