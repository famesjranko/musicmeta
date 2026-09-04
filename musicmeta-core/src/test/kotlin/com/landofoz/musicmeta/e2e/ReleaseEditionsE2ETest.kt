package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.assertNotDrift
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What a live release browse yields for one release group, checked against the API rather than a
 * capture.
 *
 * Three facts only a live call can establish. That the browse route reaches more editions than the
 * release group's own inline `releases` array, which MusicBrainz caps at 25 — a fixture cannot show
 * that cap being escaped, because a fixture holds whatever it was written to hold. That the live
 * browse's `inc` really carries `media`, so an edition's format is populated rather than null. And
 * that it really carries `labels`, so label and catalogue number arrive together on the same
 * edition. Counts and presence only: which editions MusicBrainz holds for an album is editable
 * data that moves week to week.
 *
 * It is not coverage for the parsing or the route:
 * `MusicBrainzProviderTest.enrichAlbumEditions browses releases by group, asking for the fields an
 * edition promises` pins the URL, `MusicBrainzProviderTest.enrichAlbumEditions fills format, label
 * and catalogue number from a captured browse` pins the mapped values off the committed
 * `musicbrainz-release-group-editions` pool, and
 * `MusicBrainzParserTest.toReleaseEditions maps MusicBrainzEdition list to ReleaseEditions with
 * correct fields` pins the field mapping. Those gate a merge and stay the evidence. Under
 * `-Dinclude.e2e=true` only, so a MusicBrainz outage never fails a build.
 *
 * Run manually: ./gradlew :musicmeta-core:test -Dinclude.e2e=true --tests "*ReleaseEditions*"
 */
class ReleaseEditionsE2ETest {

    private val provider = MusicBrainzProvider(E2ETestFixture.httpClient, E2ETestFixture.mbRateLimiter)

    @Test
    fun `a live release browse returns more editions than an inline release list can hold`() = runBlocking {
        // Given - e2e enabled, and the release group the committed editions pool was captured from
        assumeTrue(E2ETestFixture.prop("include.e2e") == "true")
        val request = EnrichmentRequest.forAlbum("Hail to the Thief", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzReleaseGroupId = HAIL_TO_THE_THIEF_RELEASE_GROUP_MBID))

        // When - enriching for RELEASE_EDITIONS, the one call the browse route serves
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - editions came back, past the inline cap, carrying format and label detail
        val success = assertNotDrift("Hail to the Thief release editions", result) ?: return@runBlocking
        val data = success.data
        assertTrue("expected ReleaseEditions, got $data", data is EnrichmentData.ReleaseEditions)
        val editions = (data as EnrichmentData.ReleaseEditions).editions
        assertTrue("expected >$INLINE_RELEASE_CAP editions, got ${editions.size}", editions.size > INLINE_RELEASE_CAP)
        assertTrue(
            "no edition carried a format, out of ${editions.size} editions",
            editions.any { it.format != null },
        )
        assertTrue(
            "no edition carried both a label and a catalogue number, out of ${editions.size} editions",
            editions.any { it.label != null && it.catalogNumber != null },
        )
        println(
            "ReleaseEditions live counts: editions=${editions.size}" +
                " format=${editions.count { it.format != null }}" +
                " label=${editions.count { it.label != null }}" +
                " catalogue=${editions.count { it.catalogNumber != null }}",
        )
    }

    private companion object {
        /** Radiohead, *Hail to the Thief* — the group the `musicbrainz-release-group-editions` pool holds. */
        const val HAIL_TO_THE_THIEF_RELEASE_GROUP_MBID = "5c14fd50-a2f1-3672-9537-b0dad91bea2f"

        /** A release-group lookup inlines at most 25 releases; clearing it is what the browse buys. */
        const val INLINE_RELEASE_CAP = 25
    }
}
