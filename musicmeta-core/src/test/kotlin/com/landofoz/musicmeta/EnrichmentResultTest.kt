package com.landofoz.musicmeta

import org.junit.Assert.*
import org.junit.Test

class EnrichmentResultTest {

    @Test fun `ErrorKind has all six values`() {
        // Given / When — access all enum values
        val values = ErrorKind.entries

        // Then — exactly 6 values in the expected order
        assertEquals(6, values.size)
        assertEquals(ErrorKind.NETWORK, values[0])
        assertEquals(ErrorKind.AUTH, values[1])
        assertEquals(ErrorKind.PARSE, values[2])
        assertEquals(ErrorKind.RATE_LIMIT, values[3])
        assertEquals(ErrorKind.TIMEOUT, values[4])
        assertEquals(ErrorKind.UNKNOWN, values[5])
    }

    @Test fun `Error defaults errorKind to UNKNOWN`() {
        // Given / When — construct Error without explicit errorKind
        val error = EnrichmentResult.Error(
            type = EnrichmentType.ALBUM_ART,
            provider = "test",
            message = "something failed",
        )

        // Then — defaults to UNKNOWN
        assertEquals(ErrorKind.UNKNOWN, error.errorKind)
    }

    @Test fun `Error preserves explicit errorKind`() {
        // Given / When — construct Error with explicit NETWORK errorKind
        val error = EnrichmentResult.Error(
            type = EnrichmentType.ALBUM_ART,
            provider = "test",
            message = "timeout",
            cause = null,
            errorKind = ErrorKind.NETWORK,
        )

        // Then — errorKind is NETWORK
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    @Test fun `existing 4-arg construction defaults to UNKNOWN`() {
        // Given / When — use the existing 4-argument pattern used by all providers
        val cause = RuntimeException("boom")
        val error = EnrichmentResult.Error(
            type = EnrichmentType.GENRE,
            provider = "lastfm",
            message = cause.message ?: "Unknown error",
            cause = cause,
        )

        // Then — errorKind defaults to UNKNOWN (backward compatible)
        assertEquals(ErrorKind.UNKNOWN, error.errorKind)
        assertEquals("boom", error.message)
        assertSame(cause, error.cause)
    }

    @Test fun `each ErrorKind value is distinct`() {
        // Given — all error kinds
        val kinds = listOf(
            ErrorKind.NETWORK,
            ErrorKind.AUTH,
            ErrorKind.PARSE,
            ErrorKind.RATE_LIMIT,
            ErrorKind.TIMEOUT,
            ErrorKind.UNKNOWN,
        )

        // Then — all distinct
        assertEquals(6, kinds.toSet().size)
    }
}
