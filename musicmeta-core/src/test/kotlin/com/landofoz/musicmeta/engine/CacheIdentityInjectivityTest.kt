package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor

/**
 * `CacheIdentity.key()` enumerates [EnrichmentIdentifiers]'s fields twice by hand — once in the
 * `hasIdentifiers` gate, again in the `add("id.…")` block — and joins every field through
 * length-prefixed [CacheIdentity]-private `encodePart`. Neither enumeration is bound to the data
 * class it reads, and the length-prefix is the only thing standing between a mixed request and a
 * delimiter collision. This file binds both.
 */
class CacheIdentityInjectivityTest {

    // ---- Field binding ----
    //
    // Chosen approach: reflection over EnrichmentIdentifiers's real (non-synthetic) constructor,
    // not a hand-maintained field-name list — a maintained list is the same defect the ticket
    // describes, one level up. Each constructor parameter is populated alone, with every other
    // parameter left at an empty/absent value, so a field dropped from either the `hasIdentifiers`
    // gate or the `add("id.…")` block is caught: dropping it from the gate only matters when it is
    // the sole populated identifier, which is exactly what this isolates.

    @Test fun `every EnrichmentIdentifiers constructor parameter reaches the key alone`() = runTest {
        // Given - the real constructor of EnrichmentIdentifiers, discovered by reflection rather than a maintained field list
        val ctor = identifiersPrimaryConstructor()

        // When - each parameter is populated with a marker unique to its position and every other parameter is left empty
        // Then - that marker is present in the resulting key for every parameter position
        for (index in 0 until ctor.parameterCount) {
            val marker = markerFor(index)
            val identifiers = identifiersWithOnly(ctor, index, marker)
            val request = EnrichmentRequest.ForArtist(identifiers, "Probe")
            val key = entityKeyFor(request, EnrichmentType.ARTIST_BIO)
            assertTrue(
                "constructor parameter $index of ${identifiers::class.simpleName} did not reach the key: $key",
                key.contains(marker),
            )
        }
    }

    // ---- Injectivity over a fixed adversarial corpus ----

    @Test fun `distinct requests in the adversarial corpus produce distinct keys`() = runTest {
        // Given - a fixed, deterministic corpus of requests carrying pipes, digit-only strings, blanks, non-Latin and astral-plane text
        val corpus = adversarialCorpus()

        // When - every entry's key is computed
        val keys = corpus.map { (label, request, type) -> label to entityKeyFor(request, type) }

        // Then - no two distinct corpus entries collide on their key
        val byKey = keys.groupBy({ it.second }, { it.first })
        val collisions = byKey.filterValues { it.size > 1 }
        assertTrue("distinct corpus entries collided on a key: $collisions", collisions.isEmpty())
        assertEquals(corpus.size, keys.map { it.second }.toSet().size)
    }

    @Test fun `equal requests produce equal keys`() = runTest {
        // Given - three corpus entries rebuilt as fresh, structurally-equal request instances
        val originals = adversarialCorpus().filter { it.first in setOf("pipe-in-name", "golden-collision-a", "cjk") }

        // When - each rebuilt request's key is compared with the original's key
        // Then - structurally equal requests produce identical keys
        for ((label, request, type) in originals) {
            val rebuilt = request.withIdentifiers(request.identifiers.copy())
            assertEquals(
                "rebuilt '$label' request diverged from the original's key",
                entityKeyFor(request, type),
                entityKeyFor(rebuilt, type),
            )
        }
    }

    // ---- Version and encoding shape ----

    @Test fun `the key starts with the current version and the remainder is entirely length-prefixed parts`() = runTest {
        // Given - the private VERSION constant read live so a version bump cannot silently pass a hardcoded literal
        val version = currentCacheKeyVersion()
        val request = EnrichmentRequest.ForTrack(
            EnrichmentIdentifiers(musicBrainzId = "mb-1", isrc = "ISRC1"),
            "Song",
            "Artist",
            "Album",
            1000,
        )

        // When - the primary key is computed
        val key = entityKeyFor(request, EnrichmentType.TRACK_METADATA)

        // Then - VERSION is a raw, unprefixed prefix, and everything after it parses as length-prefixed parts with nothing left over
        assertTrue(key.startsWith(version))
        assertPartsExactlyConsumeBody(key.substring(version.length))
    }

    @Test fun `the canonical-name alias key shares the primary key's version and part shape`() = runTest {
        // Given - the private VERSION constant, and a request carrying identifiers so primary and alias keys differ
        val version = currentCacheKeyVersion()
        val request = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzReleaseGroupId = "rg-1", barcode = "barcode-1"),
            "Title",
            "Artist",
        )

        // When - the alias key is computed
        val aliasKey = entityKeyForName(request, EnrichmentType.ALBUM_METADATA)

        // Then - it has the same version prefix and length-prefixed-parts shape as the primary key
        assertTrue(aliasKey.startsWith(version))
        assertPartsExactlyConsumeBody(aliasKey.substring(version.length))
    }

    // ---- Fixtures ----

    /**
     * A fixed, deterministic corpus. Every entry is constructed to be a distinct request; entries
     * `golden-collision-a`/`b` are a hand-derived pair that collides under a plain trailing-`|`
     * join of `encodePart`'s output (no length prefix) while remaining distinct requests — see the
     * `encodePart` mutation check.
     */
    private fun adversarialCorpus(): List<Triple<String, EnrichmentRequest, EnrichmentType>> = listOf(
        Triple("empty-name", EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), ""), EnrichmentType.ARTIST_BIO),
        Triple("blank-name", EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "   "), EnrichmentType.ARTIST_BIO),
        Triple("pipe-in-name", EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "A|B"), EnrichmentType.ARTIST_BIO),
        Triple(
            "digit-length-prefix-lookalike",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "5:hello"),
            EnrichmentType.ARTIST_BIO,
        ),
        Triple(
            "negative-one-length-lookalike",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "-1:"),
            EnrichmentType.ARTIST_BIO,
        ),
        Triple("non-latin", EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "Müller"), EnrichmentType.ARTIST_BIO),
        Triple("cjk", EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "日本語"), EnrichmentType.ARTIST_BIO),
        Triple(
            "astral-emoji",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "😀"),
            EnrichmentType.ARTIST_BIO,
        ),
        Triple(
            "astral-plane-han",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), "𠀀"),
            EnrichmentType.ARTIST_BIO,
        ),
        Triple(
            "extra-map-pipe-key-and-value",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(extra = mapOf("a|b" to "c|d")), "Same"),
            EnrichmentType.ARTIST_BIO,
        ),
        // The golden collision pair: distinct wikidataId/isrc splits whose plain trailing-`|`
        // concatenation is identical (verified by simulation of that exact mutation), disambiguated
        // only by encodePart's length prefix.
        Triple(
            "golden-collision-a",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(wikidataId = "X|id.isrc|Y", isrc = "Z"), "Golden"),
            EnrichmentType.ARTIST_BIO,
        ),
        Triple(
            "golden-collision-b",
            EnrichmentRequest.ForArtist(EnrichmentIdentifiers(wikidataId = "X", isrc = "Y|id.isrc|Z"), "Golden"),
            EnrichmentType.ARTIST_BIO,
        ),
        Triple(
            "album-null-optional-fields",
            EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Title", "Artist"),
            EnrichmentType.ALBUM_METADATA,
        ),
        Triple(
            "album-optional-fields-look-like-null-marker",
            EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Title", "Artist", trackCount = -1, year = -1),
            EnrichmentType.ALBUM_METADATA,
        ),
        Triple(
            "track-basic",
            EnrichmentRequest.ForTrack(EnrichmentIdentifiers(), "Song", "Artist", "Album", 1000),
            EnrichmentType.TRACK_METADATA,
        ),
        Triple(
            "track-duration-null",
            EnrichmentRequest.ForTrack(EnrichmentIdentifiers(), "Song", "Artist", "Album", null),
            EnrichmentType.TRACK_METADATA,
        ),
    )

    /** A marker unique to a constructor parameter position; never occurs incidentally in a key. */
    private fun markerFor(index: Int): String = "cache-identity-field-marker-$index"

    /**
     * [EnrichmentIdentifiers]'s real constructor: the one whose parameters are exactly its
     * declared fields, as opposed to the synthetic constructors the Kotlin default-argument and
     * `kotlinx.serialization` compiler plugins add (both take an extra marker-typed parameter),
     * and the no-arg constructor `kotlinx.serialization` also generates. It has the most
     * non-marker parameters of any declared constructor.
     */
    private fun identifiersPrimaryConstructor(): Constructor<*> =
        EnrichmentIdentifiers::class.java.declaredConstructors
            .filter { ctor -> ctor.parameterTypes.none { it.name.contains("Marker") } }
            .maxByOrNull { it.parameterCount }
            ?: error("EnrichmentIdentifiers has no constructor without a synthetic marker parameter")

    /** Builds an instance with only the parameter at [onlyIndex] populated; every other parameter is null/empty. */
    private fun identifiersWithOnly(ctor: Constructor<*>, onlyIndex: Int, marker: String): EnrichmentIdentifiers {
        val args = ctor.parameterTypes.mapIndexed { i, type ->
            when {
                type == String::class.java -> if (i == onlyIndex) marker else null
                Map::class.java.isAssignableFrom(type) ->
                    if (i == onlyIndex) mapOf("marker-key-$onlyIndex" to marker) else emptyMap<String, String>()
                else -> error("unhandled EnrichmentIdentifiers constructor parameter type $type at index $i")
            }
        }
        return ctor.newInstance(*args.toTypedArray()) as EnrichmentIdentifiers
    }

    /** Reads `CacheIdentity.VERSION` live so a version bump cannot silently pass a hardcoded literal. */
    private fun currentCacheKeyVersion(): String {
        val field = CacheIdentity::class.java.getDeclaredField("VERSION")
        field.isAccessible = true
        return field.get(null) as String
    }

    /**
     * Reproduces `encodePart`'s length-prefix grammar (`"$length:$value"`, or `"-1:"` for null,
     * with no separator between parts) and asserts [body] parses as an unbroken sequence of such
     * parts with nothing left over and nothing missing.
     */
    private fun assertPartsExactlyConsumeBody(body: String) {
        var index = 0
        var partCount = 0
        while (index < body.length) {
            val colon = body.indexOf(':', index)
            assertTrue("no length-prefix ':' found at $index in remainder: $body", colon >= 0)
            val lengthText = body.substring(index, colon)
            val length = lengthText.toIntOrNull()
            assertTrue("non-numeric length prefix '$lengthText' at $index in remainder: $body", length != null)
            requireNotNull(length)
            assertTrue("length prefix $length is neither -1 nor non-negative at $index", length == -1 || length >= 0)
            index = colon + 1
            if (length > 0) {
                val end = index + length
                assertTrue("length prefix $length overruns the remaining body at $index: $body", end <= body.length)
                index = end
            }
            partCount++
        }
        assertEquals("remainder did not parse to its exact end: $body", body.length, index)
        assertTrue("expected at least one length-prefixed part in: $body", partCount > 0)
    }
}
