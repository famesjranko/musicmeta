package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscogsMapperTest {

    // mapRoleCategory tests

    @Test
    fun `mapRoleCategory returns performance for Vocals`() {
        // Given - the role string "Vocals"
        // When - mapping it to a role category
        // Then - the category is "performance"
        assertEquals("performance", DiscogsMapper.mapRoleCategory("Vocals"))
    }

    @Test
    fun `mapRoleCategory returns production for Producer`() {
        // Given - the role string "Producer"
        // When - mapping it to a role category
        // Then - the category is "production"
        assertEquals("production", DiscogsMapper.mapRoleCategory("Producer"))
    }

    @Test
    fun `mapRoleCategory returns songwriting for Written-By`() {
        // Given - the role string "Written-By"
        // When - mapping it to a role category
        // Then - the category is "songwriting"
        assertEquals("songwriting", DiscogsMapper.mapRoleCategory("Written-By"))
    }

    @Test
    fun `mapRoleCategory returns null for unmapped role`() {
        // Given - the role string "Photography By", which has no category mapping
        // When - mapping it to a role category
        // Then - the result is null
        assertNull(DiscogsMapper.mapRoleCategory("Photography By"))
    }

    @Test
    fun `mapRoleCategory returns performance for Guitar`() {
        // Given - the role string "Guitar"
        // When - mapping it to a role category
        // Then - the category is "performance"
        assertEquals("performance", DiscogsMapper.mapRoleCategory("Guitar"))
    }

    @Test
    fun `mapRoleCategory returns performance for Bass`() {
        // Given - the role string "Bass"
        // When - mapping it to a role category
        // Then - the category is "performance"
        assertEquals("performance", DiscogsMapper.mapRoleCategory("Bass"))
    }

    @Test
    fun `mapRoleCategory returns production for Mixed By`() {
        // Given - the role string "Mixed By"
        // When - mapping it to a role category
        // Then - the category is "production"
        assertEquals("production", DiscogsMapper.mapRoleCategory("Mixed By"))
    }

    @Test
    fun `mapRoleCategory returns production for Mastered By`() {
        // Given - the role string "Mastered By"
        // When - mapping it to a role category
        // Then - the category is "production"
        assertEquals("production", DiscogsMapper.mapRoleCategory("Mastered By"))
    }

    @Test
    fun `mapRoleCategory returns songwriting for Composed By`() {
        // Given - the role string "Composed By"
        // When - mapping it to a role category
        // Then - the category is "songwriting"
        assertEquals("songwriting", DiscogsMapper.mapRoleCategory("Composed By"))
    }

    @Test
    fun `mapRoleCategory is case insensitive`() {
        // Given - role strings in mixed, lower, and hyphenated casing
        // When - mapping each to a role category
        // Then - each resolves to the same category as its canonical casing
        assertEquals("performance", DiscogsMapper.mapRoleCategory("VOCALS"))
        assertEquals("production", DiscogsMapper.mapRoleCategory("producer"))
        assertEquals("songwriting", DiscogsMapper.mapRoleCategory("written-by"))
    }

    // toCredits tests

    @Test
    fun `toCredits maps DiscogsCredit list to Credits with correct fields`() {
        // Given - two DiscogsCredit entries with distinct roles and ids
        val credits = listOf(
            DiscogsCredit(name = "John Smith", role = "Producer", id = 12345L),
            DiscogsCredit(name = "Jane Doe", role = "Vocals", id = 67890L),
        )

        // When - mapping to Credits
        val result = DiscogsMapper.toCredits(credits)

        // Then - both credits carry their mapped roleCategory and discogsArtistId
        assertEquals(2, result.credits.size)
        val first = result.credits[0]
        assertEquals("John Smith", first.name)
        assertEquals("Producer", first.role)
        assertEquals("production", first.roleCategory)
        assertEquals("12345", first.identifiers.get("discogsArtistId"))

        val second = result.credits[1]
        assertEquals("Jane Doe", second.name)
        assertEquals("Vocals", second.role)
        assertEquals("performance", second.roleCategory)
        assertEquals("67890", second.identifiers.get("discogsArtistId"))
    }

    @Test
    fun `toCredits assigns roleCategory via mapRoleCategory`() {
        // Given - role with no category mapping
        val credits = listOf(
            DiscogsCredit(name = "Someone", role = "Photography By", id = null),
        )

        // When - mapping to Credits
        val result = DiscogsMapper.toCredits(credits)

        // Then - roleCategory is null for unmapped roles
        assertEquals(1, result.credits.size)
        assertNull(result.credits[0].roleCategory)
    }

    @Test
    fun `toCredits sets empty identifiers when credit id is null`() {
        // Given - credit with no Discogs artist ID
        val credits = listOf(
            DiscogsCredit(name = "John Doe", role = "Producer", id = null),
        )

        // When - mapping to Credits
        val result = DiscogsMapper.toCredits(credits)

        // Then - no discogsArtistId in identifiers
        assertNull(result.credits[0].identifiers.get("discogsArtistId"))
    }

    @Test
    fun `toCredits returns empty Credits for empty list`() {
        // Given - an empty DiscogsCredit list
        val credits = emptyList<DiscogsCredit>()

        // When - mapping to Credits
        val result = DiscogsMapper.toCredits(credits)

        // Then - no credits are produced
        assertEquals(0, result.credits.size)
    }

    @Test
    fun `toAlbumMetadata reports a country name as its alpha-2 code and keeps a region label`() {
        // Given - two Discogs releases, one from a country and one from Discogs' Europe region
        val german = DiscogsRelease(
            title = "Trans-Europe Express",
            label = "Kling Klang",
            year = "1977",
            country = "Germany",
            coverImage = null,
        )
        val european = german.copy(country = "Europe")

        // When - mapping each to album metadata
        val germanMetadata = DiscogsMapper.toAlbumMetadata(german)
        val europeanMetadata = DiscogsMapper.toAlbumMetadata(european)

        // Then - the country becomes a code, while the region survives as Discogs wrote it
        assertEquals("DE", germanMetadata.country)
        assertEquals("Europe", europeanMetadata.country)
    }

    // toReleaseEditions tests

    @Test
    fun `toReleaseEditions maps DiscogsMasterVersion list to ReleaseEditions with correct fields`() {
        // Given - a single DiscogsMasterVersion with all fields populated
        val versions = listOf(
            DiscogsMasterVersion(
                id = 12345L,
                title = "OK Computer",
                format = "Vinyl, LP",
                label = "Parlophone",
                country = "UK",
                year = 1997,
                catno = "NODATA 01",
            ),
        )

        // When - mapping to ReleaseEditions
        val result = DiscogsMapper.toReleaseEditions(versions)

        // Then - the edition carries all the mapped fields
        assertTrue(result is EnrichmentData.ReleaseEditions)
        assertEquals(1, result.editions.size)
        val edition = result.editions[0]
        assertEquals("OK Computer", edition.title)
        assertEquals("Vinyl, LP", edition.format)
        assertEquals("GB", edition.country)
        assertEquals(1997, edition.year)
        assertEquals("Parlophone", edition.label)
        assertEquals("NODATA 01", edition.catalogNumber)
        assertNull(edition.barcode)
    }

    @Test
    fun `toReleaseEditions stores discogsReleaseId in identifiers when version id is positive`() {
        // Given - a version with a positive id
        val versions = listOf(
            DiscogsMasterVersion(id = 99001L, title = "Some Album", format = null,
                label = null, country = null, year = null, catno = null),
        )

        // When - mapping to ReleaseEditions
        val result = DiscogsMapper.toReleaseEditions(versions)

        // Then - discogsReleaseId is stored in identifiers
        assertEquals("99001", result.editions[0].identifiers.get("discogsReleaseId"))
    }

    @Test
    fun `toReleaseEditions omits discogsReleaseId when version id is 0`() {
        // Given - a version with id 0 (no real release id)
        val versions = listOf(
            DiscogsMasterVersion(id = 0L, title = "Some Album", format = null,
                label = null, country = null, year = null, catno = null),
        )

        // When - mapping to ReleaseEditions
        val result = DiscogsMapper.toReleaseEditions(versions)

        // Then - discogsReleaseId is absent from identifiers
        assertNull(result.editions[0].identifiers.get("discogsReleaseId"))
    }

    @Test
    fun `toReleaseEditions handles empty versions list`() {
        // Given - an empty DiscogsMasterVersion list
        val versions = emptyList<DiscogsMasterVersion>()

        // When - mapping to ReleaseEditions
        val result = DiscogsMapper.toReleaseEditions(versions)

        // Then - no editions are produced
        assertTrue(result is EnrichmentData.ReleaseEditions)
        assertEquals(0, result.editions.size)
    }
}
