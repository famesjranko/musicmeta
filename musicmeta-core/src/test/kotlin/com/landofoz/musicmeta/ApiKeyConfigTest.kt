package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiKeyConfigTest {

    @Test fun `with then get round-trips every constant`() {
        // Given - an empty config
        val empty = ApiKeyConfig()

        // When - setting every ApiKey to a value naming it
        val filled = ApiKey.entries.fold(empty) { config, key -> config.with(key, "value-${key.name}") }

        // Then - each constant reads back the value it was set to
        assertEquals(ApiKey.entries.associateWith { "value-${it.name}" }, ApiKey.entries.associateWith { filled[it] })
    }

    @Test fun `with replaces a value already set for the same key`() {
        // Given - a config holding one key
        val first = ApiKeyConfig.of(ApiKey.LASTFM_API_KEY to "first")

        // When - setting the same key again
        val second = first.with(ApiKey.LASTFM_API_KEY, "second")

        // Then - the later value wins and no duplicate entry is kept
        assertEquals("second", second[ApiKey.LASTFM_API_KEY])
        assertEquals(1, second.keys.size)
    }

    @Test fun `without removes the named key and leaves the rest`() {
        // Given - a config holding two keys
        val both = ApiKeyConfig.of(
            ApiKey.LASTFM_API_KEY to "lastfm",
            ApiKey.DISCOGS_PERSONAL_TOKEN to "discogs",
        )

        // When - removing one of them
        val reduced = both.without(ApiKey.LASTFM_API_KEY)

        // Then - the removed key reads null and the other is untouched
        assertNull(reduced[ApiKey.LASTFM_API_KEY])
        assertEquals("discogs", reduced[ApiKey.DISCOGS_PERSONAL_TOKEN])
    }

    @Test fun `without a key that was never set changes nothing`() {
        // Given - a config holding one unrelated key
        val config = ApiKeyConfig.of(ApiKey.DISCOGS_PERSONAL_TOKEN to "discogs")

        // When - removing a key it does not hold
        val reduced = config.without(ApiKey.LASTFM_API_KEY)

        // Then - the config is unchanged
        assertEquals(config, reduced)
    }

    @Test fun `of with no pairs equals the empty config`() {
        // Given - the no-argument constructor's config
        val empty = ApiKeyConfig()

        // When - building one from no pairs
        val ofNothing = ApiKeyConfig.of()

        // Then - the two are equal and hold nothing
        assertEquals(empty, ofNothing)
        assertEquals(emptyMap<ApiKey, String>(), ofNothing.keys)
    }

    @Test fun `an unset key reads null rather than a blank value`() {
        // Given - an empty config
        val empty = ApiKeyConfig()

        // When - reading every constant off it
        val values = ApiKey.entries.map { empty[it] }

        // Then - every one is null
        assertEquals(List(ApiKey.entries.size) { null }, values)
    }
}
