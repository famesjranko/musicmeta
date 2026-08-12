package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EnrichmentData.Metadata] is the one payload whose answer is per-type, so the mapping from type to
 * field is enumerated rather than inferred. The `when` is exhaustive over [EnrichmentType] with no
 * `else`, which is what makes the compiler name a new type; these tests pin the readings that
 * exhaustiveness alone cannot describe.
 */
class PayloadAnswersTypeCoverageTest {

    @Test
    fun `a filled Metadata answers every EnrichmentType`() {
        // Given - a Metadata carrying every field the named types read
        val payload = EnrichmentData.Metadata(
            genres = listOf("rock"),
            label = "Parlophone",
            releaseDate = "1997-05-21",
            releaseType = "Album",
            country = "GB",
        )

        // When - asking it about every type in the enum
        val unanswered = EnrichmentType.entries.filterNot { payload.answers(it) }

        // Then - no type falls through unanswered, including any type added since
        assertEquals(emptyList<EnrichmentType>(), unanswered)
    }

    @Test
    fun `an empty Metadata answers no EnrichmentType`() {
        // Given - a Metadata with every field left at its default
        val payload = EnrichmentData.Metadata()

        // When - asking it about every type in the enum
        val answered = EnrichmentType.entries.filter { payload.answers(it) }

        // Then - nothing is claimed, so the engine demotes whatever type was asked for
        assertEquals(emptyList<EnrichmentType>(), answered)
    }

    @Test
    fun `a named type reads only its own field`() {
        // Given - a Metadata carrying a label and nothing else
        val payload = EnrichmentData.Metadata(label = "Parlophone")

        // When - asking about the type that field serves and one that reads elsewhere
        val answersLabel = payload.answers(EnrichmentType.LABEL)
        val answersCountry = payload.answers(EnrichmentType.COUNTRY)

        // Then - LABEL is answered and COUNTRY is not, despite the payload being non-empty
        assertTrue(answersLabel)
        assertFalse(answersCountry)
    }

    @Test
    fun `an empty genre list reads as absent for the grab bag`() {
        // Given - a Metadata whose only fields are lists that came back empty
        val payload = EnrichmentData.Metadata(genres = emptyList(), genreTags = emptyList())

        // When - asking the lenient grab-bag type
        val answered = payload.answers(EnrichmentType.ALBUM_METADATA)

        // Then - an empty list is absence, matching GENRE's own reading
        assertFalse(answered)
    }
}
