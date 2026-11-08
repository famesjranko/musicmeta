package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MusicBrainzCandidateSelectionTest {
    private val http = FakeHttpClient()
    private val warnings = mutableListOf<String>()
    private val logger = object : EnrichmentLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) { warnings.add(message) }
    }
    private val provider = MusicBrainzProvider(http, RateLimiter(0))
    private val track = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

    @Test
    fun `identical visible recordings collapse before the limit and retain a live choice`() = runTest {
        // Given - two captured main recordings look identical and a third is a live performance.
        http.givenJsonResponse("comment", fixture("canonical"))
        http.givenJsonResponse("recording?query", fixture("duplicates"))

        // When - the caller requests two choices.
        val found = provider.searchCandidates(track, 2)

        // Then - the best main identifier remains and the live performance gets the second slot.
        assertEquals(2, found.size)
        assertEquals("262bef76-1b10-4c8a-b2be-a9921a33b4fc", found[0].identifiers.musicBrainzId)
        assertTrue(found[1].disambiguation?.startsWith("live") == true)
    }

    @Test
    fun `merged pools keep the best score per MBID and fill the freed slot`() = runTest {
        // Given - modelled general and canonical pools repeat one recording with different scores.
        val general = fixture("overlap-general")
        val canonical = fixture("overlap-canonical")
        val reversedGeneral = org.json.JSONObject(general)
        reversedGeneral.getJSONArray("recordings").getJSONObject(1).put("score", 100)
        val reversedCanonical = org.json.JSONObject(canonical)
        reversedCanonical.getJSONArray("recordings").getJSONObject(0).put("score", 90)
        val pools = listOf(general to canonical, reversedGeneral.toString() to reversedCanonical.toString())
        val requests = listOf(track, EnrichmentRequest.forTrack("Karma Police", ""))

        // When - supplied and hinted artist searches merge the pools in either score order.
        val results = requests.flatMap { request ->
            pools.map { (broad, extra) ->
                http.givenJsonResponse("comment", extra)
                http.givenJsonResponse("recording?query", broad)
                provider.searchCandidates(request, 3, listOf("Radiohead"), logger)
            }
        }

        // Then - each recording appears once at its best score and the live choice fills the third slot.
        results.forEach { found ->
            assertEquals(
                listOf(
                    "00e9aa21-e7f9-4369-a6a8-f1087791780a",
                    "adbf08e6-2cf8-4dd3-bb5b-bdddba25da27",
                    "7366f202-39e0-4604-b235-8823b5f96003",
                ),
                found.map { it.identifiers.musicBrainzId },
            )
            assertEquals(1f, found[1].matchScore)
        }
    }

    @Test
    fun `album choices preserve differences in year country and type`() = runTest {
        // Given - modelled pressings contain one duplicate and three independently different visible fields.
        http.givenJsonResponse("release?query", fixture("album-variants"))

        // When - the caller searches for the album.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), 8)

        // Then - only the duplicate disappears and each distinct pressing remains selectable.
        assertEquals(4, found.size)
        assertTrue(found.any { it.year == 2001 })
        assertTrue(found.any { it.country == "US" })
        assertTrue(found.any { it.releaseType == "Single" })
    }

    @Test
    fun `exact ties keep upstream score order and visibly different scores`() = runTest {
        // Given - modelled identical titles have different visible scores and opposite identifier order.
        http.givenJsonResponse("recording?query", fixture("exact-scores"))

        // When - the caller searches for that title.
        val found = provider.searchCandidates(track, 8)

        // Then - both scores remain and the higher score leads.
        assertEquals(listOf(1f, 0.51f), found.map { it.matchScore })
    }

    @Test
    fun `fuzzy title score outranks disambiguation and official album evidence`() = runTest {
        // Given - the strict typo search misses and a low-score official album hit competes with the score-100 near match.
        http.givenJsonResponse("recording%3A%22Enter+Sandmann%22", """{"recordings":[]}""")
        http.givenJsonResponse("recording%3AEnter+Sandmann%7E", fixture("fuzzy"))

        // When - the caller searches for the misspelled title with its artist.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Enter Sandmann", "Metallica"), 8)

        // Then - upstream relevance keeps Enter Sandman ahead of Sandman Enters.
        assertEquals(listOf("Enter Sandman", "Sandman Enters"), found.map { it.title })
        assertEquals(listOf(1f, 0.51f), found.map { it.matchScore })
    }

    @Test
    fun `preferred artist exact album is fetched when the general pool only has a qualified title`() = runTest {
        // Given - the preferred artist has only a live-qualified album in the general pool.
        http.givenJsonResponse("artistname%3A%22Radiohead%22", fixture("album-exact"))
        http.givenJsonResponse("release?query", fixture("album-partial"))

        // When - title-only discovery receives that artist hint.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("OK Computer", ""), 8, listOf("Radiohead"), logger)

        // Then - the exact album is fetched and leads the other artist's exact album.
        assertEquals("Radiohead", found.first().artist)
        assertEquals("OK Computer", found.first().title)
        assertEquals(2, http.requestedUrls.size)
    }

    @Test
    fun `marked exact preferred album still fetches an unmarked choice`() = runTest {
        // Given - the preferred artist's exact title is present only as a marked pressing.
        val marked = org.json.JSONObject(fixture("album-exact"))
        marked.getJSONArray("releases").getJSONObject(0)
            .put("id", "marked-pressing").put("disambiguation", "EMI UDEN, made in Italy")
        http.givenJsonResponse("artistname%3A%22Radiohead%22", fixture("album-exact"))
        http.givenJsonResponse("release?query", marked.toString())

        // When - title-only discovery receives that artist hint.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("OK Computer", ""), 8, listOf("Radiohead"), logger)

        // Then - an unmarked exact title leads and the marked pressing remains selectable.
        assertEquals("Radiohead", found.first().artist)
        assertTrue(found.first().disambiguation.isNullOrBlank())
        assertTrue(found.any { it.disambiguation == "EMI UDEN, made in Italy" })
        assertEquals(2, http.requestedUrls.size)
    }

    @Test
    fun `exact preferred album already present avoids another artist query`() = runTest {
        // Given - the general pool already contains an exact title by the preferred artist.
        http.givenJsonResponse("release?query", fixture("album-exact"))

        // When - title-only discovery receives that artist hint.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("OK Computer", ""), 8, listOf("Radiohead"), logger)

        // Then - the existing row answers without another upstream request.
        assertEquals("Radiohead", found.first().artist)
        assertEquals(1, http.requestedUrls.size)
    }

    @Test
    fun `marked main recording beats covers while its unmarked recording still leads`() = runTest {
        // Given - a captured HUMBLE pool includes Kendrick Lamar's unmarked and explicit recordings and a cover.
        http.givenJsonResponse("comment", fixture("marked-canonical"))
        http.givenJsonResponse("recording?query", fixture("marked"))

        // When - Kendrick Lamar leads the artist hints.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("HUMBLE.", ""), 3, listOf("Kendrick Lamar", "TEARY EYED FOX"), logger)

        // Then - both main-artist recordings lead the cover without hiding the explicit variant.
        assertEquals(listOf("Kendrick Lamar", "Kendrick Lamar", "TEARY EYED FOX"), found.map { it.artist })
        assertNull(found[0].disambiguation)
        assertEquals("explicit", found[1].disambiguation)
    }

    @Test
    fun `supplied artist supplement failure preserves the general pool and warns`() = runTest {
        // Given - the general pool succeeds but the canonical supplement has a transport failure.
        http.givenIoException("comment")
        http.givenJsonResponse("recording?query", fixture("duplicates"))

        // When - a caller searches by artist and title.
        val found = provider.searchCandidates(track, 8, emptyList(), logger)

        // Then - the general candidates survive and the failed supplement is reported.
        assertTrue(found.isNotEmpty())
        assertTrue(warnings.any { it.contains("Canonical candidate search failed") })
    }

    @Test
    fun `hinted artist supplement failure preserves the general pool and warns`() = runTest {
        // Given - the general pool succeeds but the preferred artist query has a transport failure.
        http.givenIoException("comment")
        http.givenJsonResponse("recording?query", fixture("duplicates"))

        // When - title-only discovery requests the preferred artist's supplement.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Karma Police", ""), 8, listOf("Radiohead"), logger)

        // Then - the general candidates survive and the failed supplement is reported.
        assertTrue(found.isNotEmpty())
        assertTrue(warnings.any { it.contains("Preferred artist candidate search failed") })
    }

    @Test
    fun `matching album hint does not fetch the same recording query again`() = runTest {
        // Given - the hinted broad query already returns main recordings.
        http.givenJsonResponse("recording?query", fixture("canonical"))

        // When - discovery searches with a supplied album hint.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer"), 8)

        // Then - that successful query answers alone.
        assertTrue(found.isNotEmpty())
        assertEquals(1, http.requestedUrls.size)
    }

    @Test
    fun `missing album hint reuses the broad fallback for version choices`() = runTest {
        // Given - the album hint misses but both hintless pools answer.
        http.givenJsonResponse("release%3A", """{"recordings":[]}""")
        http.givenJsonResponse("recording?query", fixture("duplicates"))

        // When - discovery searches with an unavailable album hint.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "Unknown pressing"), 8)

        // Then - one hinted query and two different hintless queries suffice.
        assertTrue(found.isNotEmpty())
        assertEquals(3, http.requestedUrls.size)
        assertTrue(http.requestedUrls.all { it.contains("limit=100") })
    }

    @Test
    fun `bracketed title does not repeat the broad unfiltered pool`() = runTest {
        // Given - both broad and filtered queries return candidates for a bracketed title.
        http.givenJsonResponse("recording?query", fixture("duplicates"))

        // When - discovery searches for the named variant.
        val found = provider.searchCandidates(EnrichmentRequest.forTrack("Karma Police (Live)", "Radiohead"), 8)

        // Then - the existing broad pool supplies alternatives without another shallow query.
        assertTrue(found.isNotEmpty())
        assertEquals(2, http.requestedUrls.size)
        assertTrue(http.requestedUrls.all { it.contains("limit=100") })
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/find-selection/$name.json")).readText()
}
