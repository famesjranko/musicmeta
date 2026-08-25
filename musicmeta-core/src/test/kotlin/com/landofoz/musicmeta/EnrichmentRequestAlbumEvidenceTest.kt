package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [EnrichmentRequest.ForAlbum] has always held the caller's own track count and year, and several
 * providers choose between editions with them. These pin that the factory can actually supply them.
 */
class EnrichmentRequestAlbumEvidenceTest {

    @Test
    fun `an album carries the track count and year the caller supplied`() {
        // Given - a caller who knows what they scanned
        val trackCount = 12
        val year = 1997

        // When - the request is built through the factory
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", trackCount = trackCount, year = year)

        // Then - both reach the request the providers read
        assertEquals(trackCount, request.trackCount)
        assertEquals(year, request.year)
    }

    @Test
    fun `the pre-evidence factory method is still on the class for an old jar to link`() {
        // Given - the JVM signature a consumer compiled before trackCount and year existed calls
        val old = arrayOf(
            String::class.java, String::class.java, String::class.java, EnrichmentIdentifiers::class.java,
        )

        // When - looking it up the way the JVM resolves a call from already-compiled bytecode
        val method = EnrichmentRequest.Companion::class.java.getDeclaredMethod("forAlbum", *old)

        // Then - it is still there, so the old call links instead of throwing NoSuchMethodError
        assertNotNull(method)
        val built = method.invoke(EnrichmentRequest.Companion, "OK Computer", "Radiohead", null, null)
        assertEquals("OK Computer", (built as EnrichmentRequest.ForAlbum).title)
        assertNull(built.trackCount)
    }

    @Test
    fun `an album that knows neither leaves both unset rather than guessing`() {
        // Given - a caller with only a title and an artist

        // When - the request is built without the optional evidence
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // Then - nothing is invented: absent evidence stays absent
        assertNull(request.trackCount)
        assertNull(request.year)
    }
}
