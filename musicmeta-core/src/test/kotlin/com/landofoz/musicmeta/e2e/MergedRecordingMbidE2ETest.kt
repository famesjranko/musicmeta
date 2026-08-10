package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzLookup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A recording MBID MusicBrainz has merged away still answers, and answers with the recording it was
 * merged into.
 *
 * This is the one branch of the caller-supplied-MBID path that cannot be reasoned about from the
 * code: [MusicBrainzApi.lookupRecording] treats a null body as "no such recording", which drops the
 * request to the name search, so whether a stale identifier resolves to the recording it names or to
 * whatever the search ranks is upstream's decision, not ours. MusicBrainz answers 301 and
 * `DefaultHttpClient` leaves
 * `instanceFollowRedirects` at its default — only the Cover Art Archive path turns it off — so the
 * merge is invisible to the caller.
 *
 * [MERGED_MBID] was found by probing Last.fm's own recording ids against MusicBrainz, which is where
 * this case comes from in practice: Last.fm's ids were bulk-imported and are not re-synced, so a
 * `trackProfile()` on a similar-track or radio pick is the request most likely to carry one. A merge
 * is not reversible, so the id will not stop redirecting; if MusicBrainz ever merges the *target*
 * onward, this test still passes on the chained redirect.
 */
class MergedRecordingMbidE2ETest {

    private val api = MusicBrainzApi(E2ETestFixture.httpClient, E2ETestFixture.mbRateLimiter)

    @Test
    fun `a merged recording mbid resolves to the recording it was merged into`() = runBlocking {
        // Given - e2e enabled, and an identifier MusicBrainz redirects rather than holds
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")

        // When - the stale identifier is looked up exactly as a caller-supplied one is
        val json = api.lookupRecording(MERGED_MBID)

        // Then - the lookup answered, and with the surviving recording rather than the id asked for,
        // so a stale identifier reads as a hit and never reaches the fall-back-to-search path
        val found = json as? MusicBrainzLookup.Found
        assertNotNull("a merged MBID must not read as an absent recording, got $json", found)
        assertEquals(MERGE_TARGET_MBID, found!!.value.optString("id"))
    }

    private companion object {
        /** Merged away; carried by Last.fm as a current id. Verified redirecting 2026-08-11. */
        const val MERGED_MBID = "001b036d-0727-49a0-b027-13f1d4ecac8a"

        /** What [MERGED_MBID] redirects to — "cigarette smoke". */
        const val MERGE_TARGET_MBID = "f195c408-7e4b-44bf-ad40-78123109f720"
    }
}
