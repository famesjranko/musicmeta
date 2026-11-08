package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzAlbumCandidateOrderTest {
    private val http = FakeHttpClient()
    private val provider = MusicBrainzProvider(http, RateLimiter(0))

    @Test
    fun `official album precedes a same-title single`() = runTest {
        // Given - the captured single is older and has a lower MBID than an official album reissue.
        stub(rows(SINGLE, LATE_REISSUE))

        // When - the caller searches the artist's exact album title.
        val found = search()

        // Then - album evidence leads and the single remains selectable.
        assertEquals(listOf(LATE_REISSUE, SINGLE), ids(found))
    }

    @Test
    fun `equivalent albums prefer the earliest pressing before truncation`() = runTest {
        // Given - the captured pool contains a reissue, French pressing, single, missing status and original.
        stub(rows(REISSUE, FRENCH, SINGLE, NO_STATUS, ORIGINAL))

        // When - the caller asks for the full pool and then only its first result.
        val found = search()
        val limited = search(limit = 1)

        // Then - the original leads with all alternatives retained and truncation keeps that original.
        assertEquals(listOf(ORIGINAL, FRENCH, REISSUE, SINGLE, NO_STATUS), ids(found))
        assertEquals(listOf(ORIGINAL), ids(limited))
    }

    @Test
    fun `pressing date compares months and days rather than only years`() = runTest {
        // Given - two captured official albums were released in November and December of the same year.
        stub(rows(DECEMBER, ORIGINAL))

        // When - the caller searches for the album.
        val found = search()

        // Then - the November pressing leads even though its MBID sorts later.
        assertEquals(listOf(ORIGINAL, DECEMBER), ids(found))
    }

    @Test
    fun `missing date ranks below dated releases and remains selectable`() = runTest {
        // Given - an unchanged captured album has a blank date and a lower MBID than a dated reissue.
        stub(rows(NO_DATE, LATE_REISSUE))

        // When - the caller requests both candidates.
        val found = search()

        // Then - the known date leads and the undated candidate is still returned.
        assertEquals(listOf(LATE_REISSUE, NO_DATE), ids(found))
        assertEquals(null, found.last().year)
    }

    @Test
    fun `missing status ranks below known status and remains selectable`() = runTest {
        // Given - a captured 1982 album lacks status and competes with a known official single.
        stub(rows(NO_STATUS, SINGLE))

        // When - the caller requests both candidates.
        val found = search()

        // Then - known evidence leads even across types and the incomplete album is still returned.
        assertEquals(listOf(SINGLE, NO_STATUS), ids(found))
    }

    @Test
    fun `missing type ranks below known type and remains selectable`() = runTest {
        // Given - a field-omission probe removes a reissue's primary type before a known single.
        val missing = row(REISSUE).apply { getJSONObject("release-group").remove("primary-type") }
        stub(listOf(missing, row(SINGLE)))

        // When - the caller requests both candidates.
        val found = search()

        // Then - the known type leads and the incomplete candidate is still returned.
        assertEquals(listOf(SINGLE, REISSUE), ids(found))
        assertEquals(null, found.last().releaseType)
    }

    @Test
    fun `official album precedes an earlier same-title compilation`() = runTest {
        // Given - unchanged captured Beatles rows include a 1967 compilation and a 1968 studio album.
        val compilation = row("682cdf23-c89d-4594-94df-b01874be1682", "beatles-artist")
        val album = row("b169f52c-6400-4a26-81bc-ec0b735f527b", "beatles-artist")
        stub(listOf(compilation, album))

        // When - the caller searches the band's exact album title.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("The Beatles", "The Beatles"), 2)

        // Then - album identity leads the older compilation and both remain selectable.
        assertEquals(listOf(album.getString("id"), compilation.getString("id")), ids(found))
    }

    @Test
    fun `official status leads other known statuses`() = runTest {
        // Given - a captured bootleg sorts before an official reissue by MBID.
        stub(rows(BOOTLEG, LATE_REISSUE))

        // When - the caller searches the album.
        val found = search()

        // Then - official status leads while the bootleg remains selectable.
        assertEquals(listOf(LATE_REISSUE, BOOTLEG), ids(found))
    }

    @Test
    fun `marked original remains selectable and can precede an unmarked reissue`() = runTest {
        // Given - the captured November original is marked as a club edition and the reissue is unmarked.
        stub(rows(REISSUE, CLUB))

        // When - the caller searches the exact album title.
        val found = search()

        // Then - date evidence is stronger than a pressing marker and both versions remain selectable.
        assertEquals(listOf(CLUB, REISSUE), ids(found))
        assertEquals("club edition", found.first().disambiguation)
    }

    @Test
    fun `reordered upstream ties have the same deterministic order`() = runTest {
        // Given - two captured albums tie on date, type, status, score and disambiguation.
        val tied = rows(ORIGINAL, SAME_DATE)
        http.givenJsonResponsesInTurn("release?query", body(tied), body(tied.reversed()))

        // When - the same search receives opposite upstream orderings.
        val first = search()
        val second = search()

        // Then - the MBID settles the tie in the same order both times.
        assertEquals(listOf(SAME_DATE, ORIGINAL), ids(first))
        assertEquals(ids(first), ids(second))
    }

    @Test
    fun `release evidence does not promote another artist above a supplied artist`() = runTest {
        // Given - a supplied artist's single competes with another artist's earlier official album.
        stub(listOf(row(SINGLE), row(KILLER_ALBUM, "thriller-title")))

        // When - the caller supplies Michael Jackson.
        val found = search()

        // Then - release evidence cannot move Killer above the supplied artist.
        assertEquals(listOf(SINGLE, KILLER_ALBUM), ids(found))
    }

    @Test
    fun `release evidence does not promote another artist above a stubbed iTunes hint`() = runTest {
        // Given - the iTunes hint is stubbed as Michael Jackson and Killer has an earlier official album.
        stub(listOf(row(SINGLE), row(KILLER_ALBUM, "thriller-title")))

        // When - the provider receives the stubbed hint for a title-only search.
        val found = provider.searchCandidates(
            EnrichmentRequest.forAlbum("Thriller", ""), 10,
            listOf("Michael Jackson"), EnrichmentLogger.NoOp,
        )

        // Then - the hinted artist leads despite the other artist's album evidence.
        assertEquals(listOf(SINGLE, KILLER_ALBUM), ids(found))
    }

    @Test
    fun `album ordering spends no extra upstream requests`() = runTest {
        // Given - the full live pool already contains the artist named by a stubbed iTunes hint.
        http.givenJsonResponse("release?query", fixture("thriller-title"))

        // When - the provider ranks that pool with the stubbed hint.
        val found = provider.searchCandidates(
            EnrichmentRequest.forAlbum("Thriller", ""), 100,
            listOf("Michael Jackson"), EnrichmentLogger.NoOp,
        )

        // Then - all 98 distinct visible choices survive using exactly the original release-search request.
        assertEquals(98, found.size)
        assertEquals(listOf(MusicBrainzApi.releaseSearchUrl("Thriller", "", 100)), http.requestedUrls)
        assertTrue(found.any { it.releaseType == "Single" })
    }

    @Test
    fun `release ordering leaves partial-title candidates behind exact titles`() = runTest {
        // Given - the captured special edit is older than an exact-title single by the same artist.
        val exact = row("bc0cc120-24ed-47cf-abdb-bab8f581fb41")
        val partial = row("f6d3221d-8eb9-4467-b73f-7c34222bb26b")
        stub(listOf(partial, exact))

        // When - the caller searches the bare title.
        val found = search()

        // Then - pressing dates cannot promote the partial title into the exact-title group.
        assertEquals(listOf(exact.getString("id"), partial.getString("id")), ids(found))
    }

    private suspend fun search(limit: Int = 10, artist: String = "Michael Jackson"): List<SearchCandidate> =
        provider.searchCandidates(EnrichmentRequest.forAlbum("Thriller", artist), limit)

    private fun ids(found: List<SearchCandidate>): List<String?> = found.map { it.identifiers.musicBrainzId }
    private fun stub(rows: List<JSONObject>) { http.givenJsonResponse("release?query", body(rows)) }
    private fun body(rows: List<JSONObject>): String = JSONObject().put("releases", JSONArray(rows)).toString()
    private fun rows(vararg ids: String): List<JSONObject> = ids.map { row(it) }
    private fun row(id: String, name: String = "thriller-artist"): JSONObject {
        val releases = JSONObject(fixture(name)).getJSONArray("releases")
        return (0 until releases.length()).map { releases.getJSONObject(it) }.single { it.getString("id") == id }
    }
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/pools/musicbrainz-album-order/$name.json")).readText()

    private companion object {
        private const val ORIGINAL = "d57c1641-f9e7-4a40-b6d9-e156e3230137"
        private const val SAME_DATE = "39898f04-c6a6-45c7-a785-31b4143dd7e1"
        private const val DECEMBER = "959272f9-97ae-4179-aebe-950eef64ed93"
        private const val REISSUE = "031c1f26-ab8b-488c-b793-e34a9340d245"
        private const val LATE_REISSUE = "bc1c76d5-1d82-49df-a6c3-139c44fae9d1"
        private const val NO_DATE = "21108dfd-2826-4846-8cca-4d073827a31b"
        private const val FRENCH = "196971df-a7b6-3cdb-8e59-f43967cd29bf"
        private const val SINGLE = "1bc5a5c1-7e4e-4a16-80f4-32a4bcd2201f"
        private const val NO_STATUS = "4ef97444-0066-4cbc-9951-7dd49b4544d0"
        private const val CLUB = "897b7802-6317-492f-8da1-d94e3bf9cba9"
        private const val BOOTLEG = "38908dc9-aa23-4255-a88c-3ea3c5056e57"
        private const val KILLER_ALBUM = "a1f951ae-5943-4087-b281-4b153db03c06"
    }
}
