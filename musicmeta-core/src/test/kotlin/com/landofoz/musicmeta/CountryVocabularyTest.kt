package com.landofoz.musicmeta

import com.landofoz.musicmeta.provider.discogs.DiscogsMapper
import com.landofoz.musicmeta.provider.discogs.DiscogsMasterVersion
import com.landofoz.musicmeta.provider.discogs.DiscogsRelease
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzEdition
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzMapper
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzRelease
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzReleaseGroupDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One rule for `country` across every field that carries it: the ISO 3166-1 alpha-2 code where the
 * upstream names a current ISO country, otherwise the upstream's own label.
 *
 * The values are the vocabulary both upstreams were measured to emit on a 188-album sample:
 * Discogs' English names, its multi-country regions and historical states, and MusicBrainz's
 * `XE`/`XW` pseudo-codes.
 */
class CountryVocabularyTest {

    @Test
    fun `album metadata reports the alpha-2 code where Discogs names a country`() {
        // Given - a Discogs release whose country is the English name "Japan"
        val release = discogsRelease(country = "Japan")

        // When - mapping it to album metadata
        val metadata = DiscogsMapper.toAlbumMetadata(release)

        // Then - the country is the alpha-2 code
        assertEquals("JP", metadata.country)
    }

    @Test
    fun `album metadata keeps a Discogs region label that names no single country`() {
        // Given - a Discogs release pressed for a multi-country region
        val release = discogsRelease(country = "UK & Europe")

        // When - mapping it to album metadata
        val metadata = DiscogsMapper.toAlbumMetadata(release)

        // Then - the region label passes through as Discogs wrote it
        assertEquals("UK & Europe", metadata.country)
    }

    @Test
    fun `album metadata keeps a historical state Discogs still names`() {
        // Given - a Discogs release pressed in a state that is no longer a current ISO country
        val release = discogsRelease(country = "Yugoslavia")

        // When - mapping it to album metadata
        val metadata = DiscogsMapper.toAlbumMetadata(release)

        // Then - the historical name passes through rather than being dropped or guessed at
        assertEquals("Yugoslavia", metadata.country)
    }

    @Test
    fun `release editions report the alpha-2 code where Discogs names a country`() {
        // Given - a Discogs master version whose country is the English name "Japan"
        val version = discogsVersion(country = "Japan")

        // When - mapping the master's versions to release editions
        val editions = DiscogsMapper.toReleaseEditions(listOf(version))

        // Then - the edition carries the alpha-2 code, the same rule album metadata applies
        assertEquals("JP", editions.editions.single().country)
    }

    @Test
    fun `release editions keep a Discogs region label that names no single country`() {
        // Given - a Discogs master version pressed for a multi-country region
        val version = discogsVersion(country = "Europe")

        // When - mapping the master's versions to release editions
        val editions = DiscogsMapper.toReleaseEditions(listOf(version))

        // Then - the region label passes through as Discogs wrote it
        assertEquals("Europe", editions.editions.single().country)
    }

    @Test
    fun `release editions keep the UK spelling as GB, which is not what Discogs writes`() {
        // Given - a Discogs master version whose country is Discogs' "UK", not the ISO code
        val version = discogsVersion(country = "UK")

        // When - mapping the master's versions to release editions
        val editions = DiscogsMapper.toReleaseEditions(listOf(version))

        // Then - the alias resolves to GB
        assertEquals("GB", editions.editions.single().country)
    }

    @Test
    fun `album metadata keeps MusicBrainz's pseudo-code for a multi-country release`() {
        // Given - a MusicBrainz release whose country is the pseudo-code XE
        val release = musicBrainzRelease(country = "XE")

        // When - mapping it to album metadata
        val metadata = MusicBrainzMapper.toAlbumMetadata(release)

        // Then - XE passes through, since it names no single country
        assertEquals("XE", metadata.country)
    }

    @Test
    fun `release editions keep MusicBrainz's pseudo-code for a multi-country pressing`() {
        // Given - a MusicBrainz release group holding one worldwide edition
        val detail = MusicBrainzReleaseGroupDetail(
            id = "rg-1",
            title = "OK Computer",
            releases = listOf(musicBrainzEdition(country = "XW")),
        )

        // When - mapping the group's releases to editions
        val editions = MusicBrainzMapper.toReleaseEditions(detail)

        // Then - XW passes through, since it names no single country
        assertEquals("XW", editions.editions.single().country)
    }

    private fun discogsRelease(country: String?) = DiscogsRelease(
        title = "Radiohead - OK Computer",
        label = "Parlophone",
        year = "1997",
        country = country,
        coverImage = null,
        releaseType = "release",
        catno = "NODATA 01",
        genres = null,
        styles = null,
        releaseId = 1,
        masterId = 2,
    )

    private fun discogsVersion(country: String?) = DiscogsMasterVersion(
        id = 1,
        title = "OK Computer",
        format = "CD, Album",
        label = "Parlophone",
        country = country,
        year = 1997,
        catno = "NODATA 01",
    )

    private fun musicBrainzRelease(country: String?) = MusicBrainzRelease(
        id = "release-1",
        title = "OK Computer",
        artistCredit = "Radiohead",
        date = "1997-05-21",
        country = country,
        barcode = null,
        tags = emptyList(),
        label = "Parlophone",
        releaseType = "Album",
        releaseGroupId = "rg-1",
        disambiguation = null,
        score = 100,
    )

    private fun musicBrainzEdition(country: String?) = MusicBrainzEdition(
        id = "edition-1",
        title = "OK Computer",
        date = "1997-05-21",
        country = country,
        barcode = null,
        format = "CD",
        label = "Parlophone",
        catalogNumber = "NODATA 01",
    )
}
