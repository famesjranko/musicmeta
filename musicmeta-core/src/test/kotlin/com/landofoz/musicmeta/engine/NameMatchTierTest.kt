package com.landofoz.musicmeta.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NameMatchTierTest {

    private val coldplayAliases = listOf(
        AlternativeName("Coolplay", official = false),
        AlternativeName("コールドプレイ", official = true),
    )

    @Test
    fun `the entity's own name is the canonical tier`() {
        // Given - a request naming the entity itself
        val requested = "Coldplay"

        // When - tiering it against the name and its aliases
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - canonical, so nothing scales the confidence down
        assertEquals(NameMatchTier.CANONICAL, tier)
    }

    @Test
    fun `an official alias outranks an unofficial one`() {
        // Given - a name the source marks as one the entity is published under
        val requested = "コールドプレイ"

        // When - tiering it
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - the primary-alias tier, above a search hint and below the name itself
        assertEquals(NameMatchTier.PRIMARY_ALIAS, tier)
    }

    @Test
    fun `an unofficial alias is the weakest match that still counts`() {
        // Given - a misspelling the source keeps only so its own search can find the entity
        val requested = "Coolplay"

        // When - tiering it
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - the plain alias tier
        assertEquals(NameMatchTier.ALIAS, tier)
    }

    @Test
    fun `two different non-Latin names do not match each other`() {
        // Given - a Chinese alias of one artist tiered against a Japanese alias of another
        val requested = "电台司令"

        // When - tiering it, where normalization would reduce both names to the empty string
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - no match, rather than every non-Latin name matching every other
        assertEquals(NameMatchTier.NONE, tier)
    }

    @Test
    fun `an identical non-Latin name still matches`() {
        // Given - the same Japanese alias the entity actually carries
        val requested = "コールドプレイ"

        // When - tiering it against a candidate list holding that exact name
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - the guard against empty normalization does not cost a real match
        assertEquals(NameMatchTier.PRIMARY_ALIAS, tier)
    }

    @Test
    fun `a name matching nothing ranks last and carries the weakest confidence factor`() {
        // Given - a name that is neither the entity's nor any of its aliases
        val requested = "Radiohead"

        // When - tiering it
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - NONE ranks last and scales confidence below every real name match, so a candidate
        // picked on a non-name signal can never report as strongly as one whose name agreed
        assertEquals(NameMatchTier.NONE, tier)
        assertEquals(0.7f, tier.confidenceFactor, 0.001f)
        assertEquals(NameMatchTier.entries.last(), tier)
    }

    @Test
    fun `a candidate whose own record holds the requested name is canonical`() {
        // Given - an alias-tier match whose source files the requested name on the candidate itself
        val tier = NameMatchTier.ALIAS

        // When - corroborating it against the names that record carries
        val corroborated = tier.corroboratedBy("コールドプレイ", listOf("Coldplay", "コールドプレイ"))

        // Then - the answering source names the entity as asked, so nothing scales the score down
        assertEquals(NameMatchTier.CANONICAL, corroborated)
    }

    @Test
    fun `a record that does not hold the requested name leaves the tier as it was`() {
        // Given - an alias-tier match whose source files only other names on the candidate
        val tier = NameMatchTier.PRIMARY_ALIAS

        // When - corroborating it against those names
        val corroborated = tier.corroboratedBy("コールドプレイ", listOf("Chris Martin", "Cold Play Live"))

        // Then - absent corroboration the pool's own tier stands, neither raised nor rejected
        assertEquals(NameMatchTier.PRIMARY_ALIAS, corroborated)
    }

    @Test
    fun `corroboration is same-name only`() {
        // Given - a record naming the entity as part of a longer credit
        val tier = NameMatchTier.ALIAS

        // When - corroborating a request the credit merely contains
        val corroborated = tier.corroboratedBy("Radiohead", listOf("Radiohead & Thom Yorke"))

        // Then - containment is not a name, so the alias tier stands
        assertEquals(NameMatchTier.ALIAS, corroborated)
    }

    @Test
    fun `a candidate no name form matched is still corroborated by the record's own names`() {
        // Given - a candidate the requested name and the alias pool both failed
        val tier: NameMatchTier? = null

        // When - the record itself turns out to hold the requested name
        val corroborated = tier.corroboratedBy("コールドプレイ", listOf("コールドプレイ"))

        // Then - the source's own claim identifies it, where no cross-source claim could
        assertEquals(NameMatchTier.CANONICAL, corroborated)
    }
}
