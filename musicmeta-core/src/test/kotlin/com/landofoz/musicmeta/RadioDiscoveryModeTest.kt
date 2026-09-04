package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioDiscoveryModeTest {

    @Test fun `each mode carries the wire word LB Radio's prompt expects`() {
        // Given - the modes a consumer can select
        val modes = RadioDiscoveryMode.entries

        // When - reading the value sent as the LB Radio mode parameter
        val wireWords = modes.associate { it.name to it.apiValue }

        // Then - each is the lowercase word the endpoint accepts, unchanged by the visibility narrowing
        assertEquals(
            mapOf("EASY" to "easy", "MEDIUM" to "medium", "HARD" to "hard"),
            wireWords,
        )
    }
}
