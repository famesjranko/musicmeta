package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzAlbumComponentOrderTest {
    @Test
    fun `combined album and single groups sort once within an individual artist tier`() = runTest {
        // Given - modelled captured rows connect two albums through a release group and a single through its artist set.
        val provider = provider(modelPool("Single"))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz")

        // When - the same-tier pool is requested in full and with two result slots.
        val all = provider.searchCandidates(request, 10)
        val small = provider.searchCandidates(request, 2)

        // Then - both albums lead the single and the older album from each group remains in the small result.
        assertEquals(listOf(C, A, B), all.map { it.identifiers.musicBrainzId })
        assertEquals(listOf(C, A), small.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `combined album groups preserve chronology within an individual artist tier`() = runTest {
        // Given - the modelled middle row is also an album and all three rows credit the supplied individual artist.
        val provider = provider(modelPool("Album"))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz")

        // When - connected release and artist groups are ranked through the provider.
        val found = provider.searchCandidates(request, 10)

        // Then - the 1970 guest edition leads 1980 and 1990 in chronological order.
        assertEquals(listOf(C, A, B), found.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `full credit artist tier keeps its album ahead of its single`() = runTest {
        // Given - the modelled guest credit is a weaker full-name match than the duet credit on the album and single.
        val provider = provider(modelPool("Single"))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz & João Gilberto")

        // When - the full-credit query requests the pool and its first two choices.
        val all = provider.searchCandidates(request, 10)
        val small = provider.searchCandidates(request, 2)

        // Then - the full-credit album remains first and the weaker guest-credit tier stays after the duet's choices.
        assertEquals(listOf(A, B, C), all.map { it.identifiers.musicBrainzId })
        assertEquals(listOf(A, B), small.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `full credit artist tier preserves chronology between its albums`() = runTest {
        // Given - two modelled duet albums outrank the different full guest credit by supplied-artist evidence.
        val provider = provider(modelPool("Album"))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz & João Gilberto")

        // When - the full-credit query ranks all editions.
        val found = provider.searchCandidates(request, 10)

        // Then - the duet albums keep their 1980 then 1990 order ahead of the weaker artist tier.
        assertEquals(listOf(A, B, C), found.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `release relation preserves a supplied guest artist tier`() = runTest {
        // Given - unchanged captured rows credit Jobim only on the later edition in the same release group.
        val provider = provider(listOf(row(DUET), row(GUEST)))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Antônio Carlos Jobim")

        // When - Jobim is supplied as the requested artist.
        val found = provider.searchCandidates(request, 2)

        // Then - the credited Jobim edition keeps priority over the older duet that does not credit Jobim.
        assertEquals(listOf(GUEST, DUET), found.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `release relation preserves a hinted guest artist tier`() = runTest {
        // Given - the same unchanged pool receives Jobim as its only stubbed artist hint.
        val provider = provider(listOf(row(DUET), row(GUEST)))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "")

        // When - the title-only search ranks with the Jobim hint.
        val found = provider.searchCandidates(request, 2, listOf("Antônio Carlos Jobim"), EnrichmentLogger.NoOp)

        // Then - the hinted guest edition leads the duet despite its later pressing date.
        assertEquals(listOf(GUEST, DUET), found.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `a common first artist alone does not connect different groups and credit sets`() = runTest {
        // Given - a modelled duet and an older solo release share a first artist but neither relation key.
        val pool = modelPool("Album")
        val duet = pool.first()
        val solo = pool.last().apply {
            val credit = JSONObject(duet.getJSONArray("artist-credit").getJSONObject(0).toString()).put("joinphrase", "")
            put("artist-credit", JSONArray().put(credit))
            getJSONObject("release-group").put("id", pool[1].getJSONObject("release-group").getString("id"))
        }
        val provider = provider(listOf(duet, solo))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz")

        // When - the individual-artist query places both rows in the same artist tier.
        val found = provider.searchCandidates(request, 2)

        // Then - the unrelated solo release cannot use chronology to take the duet's higher-relevance slot.
        assertEquals(listOf(A, C), found.map { it.identifiers.musicBrainzId })
    }

    private fun modelPool(type: String): List<JSONObject> {
        val album = row(DUET).apply { put("id", A).put("date", "1980") }
        val other = JSONObject(album.toString()).apply {
            put("id", B).put("date", "1990")
            getJSONObject("release-group").put("id", "00000000-0000-0000-0000-000000000099").put("primary-type", type)
        }
        val guest = row("3dbe8250-a4e4-4bc4-a32c-db0e3a88375d").apply { put("id", C).put("date", "1970") }
        return listOf(album, other, guest)
    }

    private fun provider(rows: List<JSONObject>): MusicBrainzProvider {
        val http = FakeHttpClient().apply {
            givenJsonResponse("release?query", JSONObject().put("releases", JSONArray(rows)).toString())
        }
        return MusicBrainzProvider(http, RateLimiter(0))
    }

    private fun row(id: String): JSONObject {
        val body = requireNotNull(javaClass.getResource("/pools/musicbrainz-album-order/getz-gilberto.json")).readText()
        val rows = JSONObject(body).getJSONArray("releases")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id }
    }

    private companion object {
        private const val A = "00000000-0000-0000-0000-000000000001"
        private const val B = "00000000-0000-0000-0000-000000000002"
        private const val C = "00000000-0000-0000-0000-000000000003"
        private const val DUET = "1cb544a7-f1d6-4159-9661-8645cbaf257c"
        private const val GUEST = "2dd57364-4c6c-4538-9529-a103dc2f8893"
    }
}
