package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.EnrichmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscogsMapperTest {

    // mapRoleCategory tests

    @Test
    fun `mapRoleCategory returns performance for Vocals`() {
        assertEquals("performance", DiscogsMapper.mapRoleCategory("Vocals"))
    }

    @Test
    fun `mapRoleCategory returns production for Producer`() {
        assertEquals("production", DiscogsMapper.mapRoleCategory("Producer"))
    }

    @Test
    fun `mapRoleCategory returns songwriting for Written-By`() {
        assertEquals("songwriting", DiscogsMapper.mapRoleCategory("Written-By"))
    }

    @Test
    fun `mapRoleCategory returns null for unmapped role`() {
        assertNull(DiscogsMapper.mapRoleCategory("Photography By"))
    }

    @Test
    fun `mapRoleCategory returns performance for Guitar`() {
        assertEquals("performance", DiscogsMapper.mapRoleCategory("Guitar"))
    }

    @Test
    fun `mapRoleCategory returns performance for Bass`() {
        assertEquals("performance", DiscogsMapper.mapRoleCategory("Bass"))
    }

    @Test
    fun `mapRoleCategory returns production for Mixed By`() {
        assertEquals("production", DiscogsMapper.mapRoleCategory("Mixed By"))
    }

    @Test
    fun `mapRoleCategory returns production for Mastered By`() {
        assertEquals("production", DiscogsMapper.mapRoleCategory("Mastered By"))
    }

    @Test
    fun `mapRoleCategory returns songwriting for Composed By`() {
        assertEquals("songwriting", DiscogsMapper.mapRoleCategory("Composed By"))
    }

    @Test
    fun `mapRoleCategory is case insensitive`() {
        assertEquals("performance", DiscogsMapper.mapRoleCategory("VOCALS"))
        assertEquals("production", DiscogsMapper.mapRoleCategory("producer"))
        assertEquals("songwriting", DiscogsMapper.mapRoleCategory("written-by"))
    }

    // toCredits tests

    @Test
    fun `toCredits maps DiscogsCredit list to Credits with correct fields`() {
        // Given
        val credits = listOf(
            DiscogsCredit(name = "John Smith", role = "Producer", id = 12345L),
            DiscogsCredit(name = "Jane Doe", role = "Vocals", id = 67890L),
        )

        // When
        val result = DiscogsMapper.toCredits(credits)

        // Then
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
        // Given — role with no category mapping
        val credits = listOf(
            DiscogsCredit(name = "Someone", role = "Photography By", id = null),
        )

        // When
        val result = DiscogsMapper.toCredits(credits)

        // Then — roleCategory is null for unmapped roles
        assertEquals(1, result.credits.size)
        assertNull(result.credits[0].roleCategory)
    }

    @Test
    fun `toCredits sets empty identifiers when credit id is null`() {
        // Given — credit with no Discogs artist ID
        val credits = listOf(
            DiscogsCredit(name = "John Doe", role = "Producer", id = null),
        )

        // When
        val result = DiscogsMapper.toCredits(credits)

        // Then — no discogsArtistId in identifiers
        assertNull(result.credits[0].identifiers.get("discogsArtistId"))
    }

    @Test
    fun `toCredits returns empty Credits for empty list`() {
        // Given
        val credits = emptyList<DiscogsCredit>()

        // When
        val result = DiscogsMapper.toCredits(credits)

        // Then
        assertEquals(0, result.credits.size)
    }
}
