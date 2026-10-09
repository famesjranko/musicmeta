package com.landofoz.musicmeta

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Payloads cached before `attribution` existed still read, so adding the field needs no cache clear.
 *
 * The bodies below are literal strings, as a persisted Room entry holds them, and were produced by
 * encoding `Biography`, `Artwork` with alternatives and `ArtworkSource` on the build that had no
 * `attribution` field. Round-trip tests cannot show this: they encode and decode with the same tree.
 *
 * A constructor parameter with a default is optional on decode, so a missing key takes the default.
 * Removing `= null` from the new field is what would turn every entry on a user's phone into a
 * decode failure.
 */
class AttributionCacheCompatTest {

    /** The strictest reader: unknown keys fail. An old payload has none, so it must still read. */
    private val strict = Json { encodeDefaults = true }

    /** The reader `RoomEnrichmentCache` uses. */
    private val lenient = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val biography = EnrichmentData.Biography(
        text = "Radiohead are an English rock band formed in Abingdon.",
        source = "Wikipedia",
        language = "en",
        thumbnailUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/330px-Radiohead.jpg",
    )

    @Test
    fun `a biography cached before the field existed decodes intact with no attribution`() {
        // Given - a Biography as the build without attribution encoded it, with no attribution key
        val cached = """{"text":"Radiohead are an English rock band formed in Abingdon.","source":"Wikipedia","language":"en","thumbnailUrl":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/330px-Radiohead.jpg"}"""

        // When - the entry is decoded by both readers
        val decoded = listOf(strict, lenient).map { it.decodeFromString<EnrichmentData.Biography>(cached) }

        // Then - the text and thumbnail are intact and the attribution is unknown rather than a failure
        assertEquals(listOf(biography, biography), decoded)
        decoded.forEach { assertNull(it.attribution) }
    }

    @Test
    fun `a biography cached as the sealed type before the field existed decodes through the type tag`() {
        // Given - the polymorphic form the cache stores, with its type discriminator and no attribution key
        val cached = """{"type":"com.landofoz.musicmeta.EnrichmentData.Biography","text":"Radiohead are an English rock band formed in Abingdon.","source":"Wikipedia","language":"en","thumbnailUrl":"https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/Radiohead.jpg/330px-Radiohead.jpg"}"""

        // When - the entry is decoded as EnrichmentData, as RoomEnrichmentCache does
        val decoded = listOf(strict, lenient).map { it.decodeFromString<EnrichmentData>(cached) }

        // Then - it is the same Biography with no attribution
        assertEquals(listOf<EnrichmentData>(biography, biography), decoded)
    }

    @Test
    fun `an artwork with alternatives cached before the field existed decodes intact with no attribution anywhere`() {
        // Given - an Artwork with sizes and one alternative, as the build without attribution encoded it
        val cached = """{"url":"https://coverartarchive.org/release-group/1/front-1200.jpg","width":1200,"height":1200,"thumbnailUrl":"https://coverartarchive.org/release-group/1/front-250.jpg","sizes":[{"url":"https://coverartarchive.org/release-group/1/front-500.jpg","width":500,"height":500,"label":"500"}],"alternatives":[{"provider":"deezer","url":"https://cdn.example.test/cover.jpg","thumbnailUrl":"https://cdn.example.test/cover-small.jpg","sizes":[{"url":"https://cdn.example.test/cover-big.jpg","width":1000,"height":1000,"label":"big"}]}]}"""
        val expected = EnrichmentData.Artwork(
            url = "https://coverartarchive.org/release-group/1/front-1200.jpg",
            width = 1200,
            height = 1200,
            thumbnailUrl = "https://coverartarchive.org/release-group/1/front-250.jpg",
            sizes = listOf(ArtworkSize("https://coverartarchive.org/release-group/1/front-500.jpg", 500, 500, "500")),
            alternatives = listOf(
                ArtworkSource(
                    provider = "deezer",
                    url = "https://cdn.example.test/cover.jpg",
                    thumbnailUrl = "https://cdn.example.test/cover-small.jpg",
                    sizes = listOf(ArtworkSize("https://cdn.example.test/cover-big.jpg", 1000, 1000, "big")),
                ),
            ),
        )

        // When - the entry is decoded by both readers
        val decoded = listOf(strict, lenient).map { it.decodeFromString<EnrichmentData.Artwork>(cached) }

        // Then - every image and size is intact, and neither the artwork nor its alternative has attribution
        assertEquals(listOf(expected, expected), decoded)
        decoded.forEach {
            assertNull(it.attribution)
            assertNull(it.alternatives?.single()?.attribution)
        }
    }

    @Test
    fun `an artwork source cached before the field existed decodes with no attribution`() {
        // Given - an ArtworkSource as the build without attribution encoded it, with explicit nulls
        val cached = """{"provider":"itunes","url":"https://is1.example.test/art.jpg","thumbnailUrl":null,"sizes":null}"""

        // When - the entry is decoded by both readers
        val decoded = listOf(strict, lenient).map { it.decodeFromString<ArtworkSource>(cached) }

        // Then - the provider and URL are intact and the attribution is unknown
        val expected = ArtworkSource(provider = "itunes", url = "https://is1.example.test/art.jpg")
        assertEquals(listOf(expected, expected), decoded)
    }
}
