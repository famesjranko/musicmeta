package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicBrainzAlbumEditionOrderTest {
    @Test
    fun `artist credit ids preserve upstream credit order`() {
        // Given - the captured original credits Getz, Gilberto and Jobim in that order.
        val original = row("getz-gilberto", "3dbe8250-a4e4-4bc4-a32c-db0e3a88375d")

        // When - the release is parsed from the upstream response shape.
        val found = MusicBrainzParser.parseReleases(JSONObject().put("releases", JSONArray().put(original))).single()

        // Then - the first credited artist and every following id retain their upstream positions.
        assertEquals(
            listOf("8f2422ab-0ec6-4c92-80c4-afe9622fab32", "286ec4c2-b5ca-4f85-b331-280a6d73dd14", "7a8dbe84-f4c0-4457-bfa3-edced1f8cde0"),
            found.artistCreditIds,
        )
    }

    @Test
    fun `different first artists keep separate edition slots despite a shared guest`() {
        // Given - a boundary pool has a Getz and Gilberto duet followed by an earlier Jobim and Getz edition.
        val duet = parsed("getz-gilberto", "1cb544a7-f1d6-4159-9661-8645cbaf257c")
        val original = parsed("getz-gilberto", "3dbe8250-a4e4-4bc4-a32c-db0e3a88375d")
        val other = original.copy(
            artistCredit = "Antônio Carlos Jobim feat. Stan Getz",
            artistCredits = listOf(original.artistCredits.last(), duet.artistCredits.first()),
            artistCreditIds = listOf(original.artistCreditIds.last(), duet.artistCreditIds.first()),
        )

        // When - equivalent-title editions in the same release group are ordered.
        val found = MusicBrainzAlbumCandidateOrder.order(listOf(duet, other), "Getz/Gilberto") { it }

        // Then - a shared guest cannot move an edition with a different first artist into the duet's slot.
        assertEquals(listOf(duet.id, other.id), found.map { it.id })
    }

    @Test
    fun `first credited artist cannot merge a solo release across groups above a supplied collaborator`() = runTest {
        // Given - a boundary pool has Getz and Gilberto together and an earlier Getz solo release in another group.
        val duet = row("getz-gilberto", "1cb544a7-f1d6-4159-9661-8645cbaf257c")
        val soloArtist = JSONObject(duet.getJSONArray("artist-credit").getJSONObject(0).toString()).put("joinphrase", "")
        val otherGroup = row("getz-gilberto", "472652de-f9b0-4dad-a836-bdd754699c9c").getJSONObject("release-group").getString("id")
        val solo = row("getz-gilberto", "3dbe8250-a4e4-4bc4-a32c-db0e3a88375d").apply {
            put("artist-credit", JSONArray().put(soloArtist))
            getJSONObject("release-group").put("id", otherGroup)
        }
        val http = FakeHttpClient().apply {
            givenJsonResponse("release?query", JSONObject().put("releases", JSONArray(listOf(duet, solo))).toString())
        }
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - the caller supplies the collaborator Gilberto rather than the first credited artist.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Getz/Gilberto", "João Gilberto"), 2)

        // Then - the shared first artist cannot move the solo release above the supplied collaborator.
        assertEquals(listOf(duet.getString("id"), solo.getString("id")), found.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `release group joins guest credit variations before truncation`() = runTest {
        // Given - the captured original adds Jobim to the reissue's Getz and Gilberto credits.
        val http = FakeHttpClient().apply { givenJsonResponse("release?query", fixture("getz-gilberto")) }
        val provider = MusicBrainzProvider(http, RateLimiter(0))
        val request = EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz")

        // When - the caller requests only the best edition from the complete captured pool.
        val found = provider.searchCandidates(request, 1)

        // Then - the 1964 original wins rather than the 2011 reissue and no lookup is needed.
        assertEquals("3dbe8250-a4e4-4bc4-a32c-db0e3a88375d", found.single().identifiers.musicBrainzId)
        assertEquals(listOf(MusicBrainzApi.releaseSearchUrl("Getz/Gilberto", "Stan Getz", 100)), http.requestedUrls)
    }

    @Test
    fun `release group cannot exchange slots across disjoint artist credits`() = runTest {
        // Given - a boundary probe assigns Radiohead's captured credit to an earlier edition in Getz's release group.
        val reissue = row("getz-gilberto", "1cb544a7-f1d6-4159-9661-8645cbaf257c")
        val unrelated = row("getz-gilberto", "3dbe8250-a4e4-4bc4-a32c-db0e3a88375d").apply {
            put("artist-credit", row("ok-computer", "1834eae1-741b-3c03-9ca5-0df3decb43ea").getJSONArray("artist-credit"))
        }
        val http = FakeHttpClient().apply {
            givenJsonResponse("release?query", JSONObject().put("releases", JSONArray(listOf(reissue, unrelated))).toString())
        }
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - the caller supplies Stan Getz as the artist.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Getz/Gilberto", "Stan Getz"), 2)

        // Then - the shared group cannot move Radiohead into Getz's slot.
        assertEquals(listOf(reissue.getString("id"), unrelated.getString("id")), found.map { it.identifiers.musicBrainzId })
    }

    @Test
    fun `overlapping guest credits cannot bridge disjoint artists`() {
        // Given - a boundary pool has a Getz and Gilberto duet, a Jobim guest credit, then a Jobim solo credit.
        val duet = parsed("getz-gilberto", "1cb544a7-f1d6-4159-9661-8645cbaf257c")
        val guest = parsed("getz-gilberto", "3dbe8250-a4e4-4bc4-a32c-db0e3a88375d")
        val solo = guest.copy(id = "ffffffff-ffff-ffff-ffff-ffffffffffff", date = "1960", artistCreditIds = listOf(guest.artistCreditIds.last()))
        val pool = listOf(duet, guest, solo)

        // When - release evidence is applied to the three slots.
        val found = MusicBrainzAlbumCandidateOrder.order(pool, "Getz/Gilberto") { it }

        // Then - the guest original leads while Jobim cannot take the disjoint duet's slot.
        assertEquals(listOf(guest.id, duet.id, solo.id), found.map { it.id })
    }

    @Test
    fun `OK Computer first pressing precedes year-only editions`() = runTest {
        // Given - the complete captured pool includes five year-only 1997 dates and the dated Japanese first pressing.
        val http = FakeHttpClient().apply { givenJsonResponse("release?query", fixture("ok-computer")) }
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - the caller asks for the first album candidate.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), 1)

        // Then - the documented May date leads the less precise dates from the same year.
        assertEquals("1834eae1-741b-3c03-9ca5-0df3decb43ea", found.single().identifiers.musicBrainzId)
    }

    @Test
    fun `Rumours first pressing precedes year-only editions`() = runTest {
        // Given - the captured pool contains year-only 1977 editions and the dated US pressing.
        val http = FakeHttpClient().apply { givenJsonResponse("release?query", fixture("rumours")) }
        val provider = MusicBrainzProvider(http, RateLimiter(0))

        // When - the caller asks for the first album candidate.
        val found = provider.searchCandidates(EnrichmentRequest.forAlbum("Rumours", "Fleetwood Mac"), 1)

        // Then - the February date leads instead of the less precise year-only rows.
        assertEquals("081ea37e-db59-4332-8cd2-ad020cb93af6", found.single().identifiers.musicBrainzId)
    }

    @Test
    fun `date precision follows chronology with partial periods last`() {
        // Given - explicit boundary dates on one captured edition cover years, months and full dates.
        val edition = parsed("ok-computer", "1834eae1-741b-3c03-9ca5-0df3decb43ea")
        val expected = listOf("1996", "1997-05-21", "1997-05", "1997-06-16", "1997-12-31", "1997", "1998-01-01")
        val rows = expected.reversed().mapIndexed { index, date -> edition.copy(id = "date-$index", date = date) }

        // When - equivalent editions are ordered by release evidence.
        val found = MusicBrainzAlbumCandidateOrder.order(rows, "OK Computer") { it }

        // Then - a partial date trails fuller dates in its own period but precedes the next period.
        assertEquals(expected, found.map { it.date })
    }

    private fun parsed(name: String, id: String): MusicBrainzRelease =
        MusicBrainzParser.parseReleases(JSONObject(fixture(name))).single { it.id == id }

    private fun row(name: String, id: String): JSONObject {
        val rows = JSONObject(fixture(name)).getJSONArray("releases")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/pools/musicbrainz-album-order/$name.json")).readText()
}
