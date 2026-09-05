package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzApi
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three discography and edition browses, checked against a real capture of the same route.
 *
 * A `requiredPaths` list is a claim about a live payload, and the only thing that can falsify it
 * without a network is a response the upstream actually sent. Each case here feeds a captured pool
 * body to [probe] under the target's own URL: a [PinVerdict.Ok] means every pinned path is really
 * where the pin says it is, and the two mutation cases below show the pin naming a path once the
 * capture stops carrying it — the failure a green daily run can never demonstrate.
 *
 * The URL case holds the other half of the pin's contract: a target asserts against the document
 * the library requests, which is only true while the route function it calls still builds that URL.
 *
 * The MusicBrainz release-group browse is pinned on Radiohead and captured from Tenacious D. That
 * is deliberate: the pin's subject is the route's field names, which no artist changes, and the
 * capture that carries an empty `first-release-date` is the harder case to hold.
 */
class SchemaPinBrowseTargetsTest {

    private fun target(provider: String, route: String): SchemaTarget {
        val all = MusicBrainzApi.SCHEMA_PIN_TARGETS + ListenBrainzApi.SCHEMA_PIN_TARGETS
        return all.single { it.provider == provider && it.route == route }
    }

    private suspend fun probeWith(pinned: SchemaTarget, body: String): PinVerdict {
        val http = FakeHttpClient()
        http.givenJsonResponse(pinned.url, body)
        return probe(http, pinned)
    }

    @Test
    fun `the release group browse pin holds against a captured release group browse`() = runTest {
        // Given - the browse pin, and the browse this library's discography really received
        val pinned = target("musicbrainz", "release group browse for artist")
        val captured = UpstreamPools.body(
            "musicbrainz-undated-release-group",
            "musicbrainz-release-group-browse.json",
        )
        // When - the pin probes a route answering with that capture
        val verdict = probeWith(pinned, captured)
        // Then - every pinned path resolved
        assertEquals(PinVerdict.Ok, verdict)
    }

    @Test
    fun `a release group browse missing first-release-date is drift, naming that path`() = runTest {
        // Given - the same capture with the first group's date removed, as an undated group sends it
        val pinned = target("musicbrainz", "release group browse for artist")
        val body = JSONObject(
            UpstreamPools.body(
                "musicbrainz-undated-release-group",
                "musicbrainz-release-group-browse.json",
            ),
        )
        body.getJSONArray("release-groups").getJSONObject(0).remove("first-release-date")
        // When - the pin probes a route answering with the mutated capture
        val verdict = probeWith(pinned, body.toString())
        // Then - it reports drift and names the one path that moved
        assertEquals(PinVerdict.Drift(listOf("release-groups[0].first-release-date")), verdict)
    }

    @Test
    fun `the release browse pin holds against a captured release browse`() = runTest {
        // Given - the edition pin, and the browse `RELEASE_EDITIONS` really received
        val pinned = target("musicbrainz", "release browse for release group")
        val captured = UpstreamPools.body(
            "musicbrainz-release-group-editions",
            "musicbrainz-release-browse.json",
        )
        // When - the pin probes a route answering with that capture
        val verdict = probeWith(pinned, captured)
        // Then - every pinned path resolved, including the group carried inside the first release
        assertEquals(PinVerdict.Ok, verdict)
    }

    @Test
    fun `the top release groups pin holds against a captured ListenBrainz answer`() = runTest {
        // Given - the ListenBrainz discography pin, and the answer that route really sent
        val pinned = target("listenbrainz", "top release groups for artist")
        val captured = UpstreamPools.body(
            "listenbrainz-top-release-groups",
            "listenbrainz-top-release-groups.json",
        )
        // When - the pin probes a route answering with that capture
        val verdict = probeWith(pinned, captured)
        // Then - every pinned path resolved
        assertEquals(PinVerdict.Ok, verdict)
    }

    @Test
    fun `each browse pin names the URL its own api client requests`() = runTest {
        // Given - an api client over a recording http client, and the three pinned URLs
        val http = FakeHttpClient()
        http.givenJsonResponse("release-group?artist=", """{"release-groups":[]}""")
        http.givenJsonResponse("release?release-group=", """{"releases":[]}""")
        http.givenJsonResponse("top-release-groups-for-artist", "[]")
        val musicBrainz = MusicBrainzApi(http, RateLimiter(0))
        val listenBrainz = ListenBrainzApi(http, RateLimiter(0))
        // When - each route is called with the arguments its pin was built from
        musicBrainz.browseReleaseGroups(RADIOHEAD_MBID, limit = 5, offset = 0)
        musicBrainz.browseReleaseGroupReleases(HAIL_TO_THE_THIEF_MBID)
        listenBrainz.getTopReleaseGroupsForArtist(LIL_WAYNE_MBID)
        // Then - each api asked for the route it has always asked for, and its pin names that URL
        val requested = listOf(
            "https://musicbrainz.org/ws/2/release-group?artist=$RADIOHEAD_MBID" +
                "&type=album%7Cep%7Csingle&fmt=json&limit=5&offset=0",
            "https://musicbrainz.org/ws/2/release?release-group=$HAIL_TO_THE_THIEF_MBID&fmt=json" +
                "&inc=release-groups+artist-credits+labels+media&limit=100",
            "https://api.listenbrainz.org/1/popularity/top-release-groups-for-artist/$LIL_WAYNE_MBID",
        )
        assertEquals(requested, http.requestedUrls)
        assertEquals(
            requested,
            listOf(
                target("musicbrainz", "release group browse for artist").url,
                target("musicbrainz", "release browse for release group").url,
                target("listenbrainz", "top release groups for artist").url,
            ),
        )
    }

    @Test
    fun `a ListenBrainz answer that unnests the release group name is drift`() = runTest {
        // Given - the same capture with the name moved back out to the flat sibling it once used
        val pinned = target("listenbrainz", "top release groups for artist")
        val body = JSONArray(
            UpstreamPools.body(
                "listenbrainz-top-release-groups",
                "listenbrainz-top-release-groups.json",
            ),
        )
        val first = body.getJSONObject(0)
        first.put("release_group_name", first.getJSONObject("release_group").getString("name"))
        first.remove("release_group")
        // When - the pin probes a route answering with the mutated capture
        val verdict = probeWith(pinned, body.toString())
        // Then - a name the mapper can still read, in a place the pin is not watching, is drift
        assertEquals(PinVerdict.Drift(listOf("[0].release_group.name")), verdict)
    }

    private companion object {
        const val RADIOHEAD_MBID = "a74b1b7f-71a5-4011-9441-d0b5e4122711"
        const val HAIL_TO_THE_THIEF_MBID = "5c14fd50-a2f1-3672-9537-b0dad91bea2f"
        const val LIL_WAYNE_MBID = "ac9a487a-d9d2-4f27-bb23-0f4686488345"
    }
}
