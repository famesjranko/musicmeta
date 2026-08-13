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
    fun `a required catalog entry absent from the live set with no key gets a key-missing row`() {
        // Given - a live set that never registered Fanart.tv, and no key configured for it either
        val live = listOf(liveInfo("musicbrainz", "MusicBrainz"))
        val keys = ApiKeyConfig(fanartTvProjectKey = null)

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - the absent Required entry appears as a catalog-only key-missing row
        val fanartRow = rows.single { it.id == "fanarttv" }
        assertEquals("KEY_MISSING", fanartRow.keyStatus)
        assertEquals(false, fanartRow.available)
        assertEquals(true, fanartRow.requiresApiKey)
        assertTrue(fanartRow.capabilities.isEmpty())
    }

    @Test
    fun `a required catalog entry absent from the live set but with its key configured gets no row`() {
        // Given - Fanart.tv is absent from the live set even though its key is configured, e.g. a
        // build that filters providers for some other reason, or a registration failure
        val live = listOf(liveInfo("musicbrainz", "MusicBrainz"))
        val keys = ApiKeyConfig(fanartTvProjectKey = "configured")

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - no row is synthesised for it: absence alone is not read as "key missing"
        assertTrue(rows.none { it.id == "fanarttv" })
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
    fun `every keyed provider live with its key configured yields no key-missing rows anywhere`() {
        // Given - Last.fm, Fanart.tv and Discogs are all live because every key was configured
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

    @Test
    fun `merged rows are ordered by the catalog, not by live registration order`() {
        // Given - the live set registers ListenBrainz before MusicBrainz, the reverse of the catalog's order,
        // and every key is configured so no catalog-only rows are synthesised to confuse the ordering
        val live = listOf(liveInfo("listenbrainz", "ListenBrainz"), liveInfo("musicbrainz", "MusicBrainz"))
        val keys = ApiKeyConfig(lastFmKey = "a", fanartTvProjectKey = "b", discogsPersonalToken = "c")

        // When - the live rows are merged with the default-provider catalog, which lists musicbrainz first
        val rows = buildProviderRows(live, keys)

        // Then - the merged order follows the catalog, not the live registration order
        assertEquals(listOf("musicbrainz", "listenbrainz"), rows.map { it.id })
    }

    @Test
    fun `a synthesised key-missing row sorts into its own catalog position, and unknown ids sort after in live order`() {
        // Given - Fanart.tv (mid-catalog, not the tail-most Required entry) is absent with no key
        // configured; Last.fm and Discogs are absent too but keyed, so neither synthesises a row;
        // two ids the catalog doesn't know register in reverse order
        val live = listOf(
            liveInfo("custom-thing-2", "Custom Thing Two"),
            liveInfo("musicbrainz", "MusicBrainz"),
            liveInfo("custom-thing-1", "Custom Thing One"),
        )
        val keys = ApiKeyConfig(lastFmKey = "configured", discogsPersonalToken = "configured")

        // When - the live rows are merged with the default-provider catalog
        val rows = buildProviderRows(live, keys)

        // Then - fanarttv sorts at its own catalog index rather than being appended to the tail, and
        // the unknown ids sort after every catalog id, keeping their original live relative order
        assertEquals(
            listOf("musicbrainz", "fanarttv", "custom-thing-2", "custom-thing-1"),
            rows.map { it.id },
        )
    }
}
