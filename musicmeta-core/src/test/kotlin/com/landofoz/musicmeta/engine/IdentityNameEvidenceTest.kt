package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A name-searching provider's [LookupProvenance] states whether MusicBrainz vouched for the name it
 * searched. That is a different question from whether identity resolution succeeded: resolution can
 * succeed by looking an identifier up, which confirms the identifier names an entity and compares no
 * name to anything.
 */
class IdentityNameEvidenceTest {

    private fun engine(http: FakeHttpClient) = DefaultEnrichmentEngine(
        ProviderRegistry(
            listOf(
                MusicBrainzProvider(http, RateLimiter(0)),
                CoverArtArchiveProvider(http, RateLimiter(0)),
                LastFmProvider("k", http, RateLimiter(0)),
            ),
        ),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    private fun http() = FakeHttpClient().apply {
        givenJsonResponse("recording/rec-1", RECORDING)
        givenJsonResponse("audioscrobbler", LASTFM_TRACK)
    }

    @Test
    fun `an identifier resolution does not vouch for a name it never compared`() = runTest {
        // Given - a track known by its recording MBID, which leaves the release-group id only
        // resolution can fill, so identity runs and resolves by that identifier
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST, mbid = "rec-1")

        // When - a type only a name-searching provider serves is enriched alongside it
        val results = engine(http()).enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART, EnrichmentType.TRACK_POPULARITY),
        )
        assertEquals(
            "identity must actually resolve, or this proves nothing",
            CanonicalStatus.RESOLVED,
            results.identity.status,
        )

        // Then - the name search is unverified. Resolution proved the identifier names a recording;
        // nothing anywhere compared the caller's title or artist to what that recording is called.
        val success = results.raw[EnrichmentType.TRACK_POPULARITY] as EnrichmentResult.Success
        assertEquals(LookupProvenance.FUZZY_NAME, success.provenance)
    }

    @Test
    fun `a name resolution does vouch for the name every other provider then searches`() = runTest {
        // Given - the same track named without an identifier, so resolution matches it by name
        val http = http()
        http.givenJsonResponse("recording?query", POOL)
        val request = EnrichmentRequest.forTrack(TITLE, ARTIST)

        // When - the same name-served type is enriched
        val results = engine(http).enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART, EnrichmentType.TRACK_POPULARITY),
        )
        assertEquals(
            "identity must actually resolve, or this proves nothing",
            CanonicalStatus.RESOLVED,
            results.identity.status,
        )

        // Then - MusicBrainz matched that very name, so the provider that searched it is confirmed
        val success = results.raw[EnrichmentType.TRACK_POPULARITY] as EnrichmentResult.Success
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
    }

    private companion object {
        const val TITLE = "Enter Sandman"
        const val ARTIST = "Metallica"

        val RECORDING = """
            {"id": "rec-1", "title": "Enter Sandman", "length": 331560,
             "artist-credit": [{"artist": {"id": "art-1", "name": "Metallica"}}],
             "releases": [{"id": "rel-1", "status": "Official",
               "release-group": {"id": "rg-1", "title": "Metallica", "primary-type": "Album"}}]}
        """.trimIndent()

        val POOL = """
            {"recordings": [{"id": "rec-1", "score": 100, "title": "Enter Sandman",
              "artist-credit": [{"artist": {"id": "art-1", "name": "Metallica"}}],
              "releases": [{"id": "rel-1", "status": "Official",
                "release-group": {"id": "rg-1", "title": "Metallica", "primary-type": "Album"}}]}]}
        """.trimIndent()

        val LASTFM_TRACK = """
            {"track": {"name": "Enter Sandman", "playcount": "1000", "listeners": "500"}}
        """.trimIndent()
    }
}
