package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.ProviderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTest {

    private fun liveInfo(id: String, displayName: String, requiresApiKey: Boolean = false) = ProviderInfo(
        id = id,
        displayName = displayName,
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, priority = 100)),
        requiresApiKey = requiresApiKey,
        isAvailable = true,
    )

    @Test
    fun `a required catalog entry absent from the live set gets a key-missing row`() {
        // Given - a live set that never registered Fanart.tv, Last.fm or Discogs (no keys configured)
        val live = listOf(liveInfo("musicbrainz", "MusicBrainz"))
        val keys = ApiKeyConfig()

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - each Required catalog entry not in the live set appears as a catalog-only key-missing row
        val fanartRow = rows.single { it.id == "fanarttv" }
        assertEquals("KEY_MISSING", fanartRow.keyStatus)
        assertEquals(false, fanartRow.available)
        assertEquals(true, fanartRow.requiresApiKey)
        assertTrue(fanartRow.capabilities.isEmpty())
    }

    @Test
    fun `a live provider is not duplicated by its own catalog entry`() {
        // Given - Last.fm is live because its key was configured
        val live = listOf(liveInfo("lastfm", "Last.fm", requiresApiKey = true))
        val keys = ApiKeyConfig(lastFmKey = "configured")

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - exactly one row for lastfm exists, and it carries the live capabilities, not a synthesised empty row
        val lastfmRows = rows.filter { it.id == "lastfm" }
        assertEquals(1, lastfmRows.size)
        assertNull(lastfmRows.single().keyStatus)
        assertTrue(lastfmRows.single().capabilities.isNotEmpty())
    }

    @Test
    fun `listenbrainz flags token-missing on its live row without dropping it`() {
        // Given - ListenBrainz always registers, but no token was configured
        val live = listOf(liveInfo("listenbrainz", "ListenBrainz"))
        val keys = ApiKeyConfig(listenBrainzToken = null)

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - the row stays live and available, marked token-missing rather than key-missing
        val row = rows.single { it.id == "listenbrainz" }
        assertEquals("TOKEN_MISSING", row.keyStatus)
        assertEquals(true, row.available)
    }

    @Test
    fun `listenbrainz carries no key status once a token is configured`() {
        // Given - ListenBrainz is live and a token was configured
        val live = listOf(liveInfo("listenbrainz", "ListenBrainz"))
        val keys = ApiKeyConfig(listenBrainzToken = "configured")

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - the row carries no key status at all
        assertNull(rows.single { it.id == "listenbrainz" }.keyStatus)
    }

    @Test
    fun `a required catalog entry with its key configured is not synthesised as missing`() {
        // Given - every keyed provider registered because every key was configured
        val live = listOf(
            liveInfo("lastfm", "Last.fm", requiresApiKey = true),
            liveInfo("fanarttv", "Fanart.tv", requiresApiKey = true),
            liveInfo("discogs", "Discogs", requiresApiKey = true),
        )
        val keys = ApiKeyConfig(
            lastFmKey = "a",
            fanartTvProjectKey = "b",
            discogsPersonalToken = "c",
        )

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - no row anywhere carries a key-missing status
        assertTrue(rows.none { it.keyStatus == "KEY_MISSING" })
    }
}
