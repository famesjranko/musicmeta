package com.landofoz.musicmeta

import org.junit.Assert.*
import org.junit.Test

class EnrichmentResultTest {

    @Test fun `ErrorKind has all seven values`() {
        // Given - the ErrorKind enum
        // When - all values are accessed
        val values = ErrorKind.entries

        // Then - exactly 7 values in the expected order
        assertEquals(7, values.size)
        assertEquals(ErrorKind.NETWORK, values[0])
        assertEquals(ErrorKind.AUTH, values[1])
        assertEquals(ErrorKind.PARSE, values[2])
        assertEquals(ErrorKind.RATE_LIMIT, values[3])
        assertEquals(ErrorKind.TIMEOUT, values[4])
        assertEquals(ErrorKind.UNKNOWN, values[5])
        assertEquals(ErrorKind.ENGINE_CLOSED, values[6])
    }

    @Test fun `Error defaults errorKind to UNKNOWN`() {
        // Given - no explicit errorKind
        // When - an Error is constructed
        val error = EnrichmentResult.Error(
            type = EnrichmentType.ALBUM_ART,
            provider = "test",
            message = "something failed",
        )

        // Then - defaults to UNKNOWN
        assertEquals(ErrorKind.UNKNOWN, error.errorKind)
    }

    @Test fun `Error preserves explicit errorKind`() {
        // Given - an explicit NETWORK errorKind
        // When - an Error is constructed with it
        val error = EnrichmentResult.Error(
            type = EnrichmentType.ALBUM_ART,
            provider = "test",
            message = "timeout",
            cause = null,
            errorKind = ErrorKind.NETWORK,
        )

        // Then - errorKind is NETWORK
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    @Test fun `existing 4-arg construction defaults to UNKNOWN`() {
        // Given - the existing 4-argument pattern used by all providers
        // When - an Error is constructed with it
        val cause = RuntimeException("boom")
        val error = EnrichmentResult.Error(
            type = EnrichmentType.GENRE,
            provider = "lastfm",
            message = cause.message ?: "Unknown error",
            cause = cause,
        )

        // Then - errorKind defaults to UNKNOWN (backward compatible)
        assertEquals(ErrorKind.UNKNOWN, error.errorKind)
        assertEquals("boom", error.message)
        assertSame(cause, error.cause)
    }

    @Test fun `each ErrorKind value is distinct`() {
        // Given - all error kinds
        val kinds = listOf(
            ErrorKind.NETWORK,
            ErrorKind.AUTH,
            ErrorKind.PARSE,
            ErrorKind.RATE_LIMIT,
            ErrorKind.TIMEOUT,
            ErrorKind.UNKNOWN,
        )

        // Then - all distinct
        assertEquals(6, kinds.toSet().size)
    }
}
