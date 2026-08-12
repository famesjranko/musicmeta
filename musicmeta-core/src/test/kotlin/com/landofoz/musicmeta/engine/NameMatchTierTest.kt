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
    fun `a name matching nothing ranks last but does not scale the confidence`() {
        // Given - a name that is neither the entity's nor any of its aliases
        val requested = "Radiohead"

        // When - tiering it
        val tier = nameMatchTier(requested, "Coldplay", coldplayAliases)

        // Then - NONE ranks last, and its factor leaves such a candidate scored as it was before
        // aliases were read: only the two alias tiers are a decided change to confidence
        assertEquals(NameMatchTier.NONE, tier)
        assertEquals(1.0f, tier.confidenceFactor, 0.001f)
        assertEquals(NameMatchTier.entries.last(), tier)
    }
}
