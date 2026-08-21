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

    @Test
    fun `every module the page imports is a file the server serves`() {
        // Given - the index.js the server actually ships, and the modules it imports by URL
        val indexJs = checkNotNull(ServerTest::class.java.getResourceAsStream("/index.js")) { "index.js missing" }
            .readBytes()
            .decodeToString()
        val imported = Regex("""from\s+'(/[^']+)'""").findAll(indexJs).map { it.groupValues[1] }.toList()

        // When - checking each against the static routes and the packaged resources
        val unserved = imported.filter { it !in STATIC_PATHS }
        val absent = imported.filter { ServerTest::class.java.getResourceAsStream(it) == null }

        // Then - every import both routes and exists, or the page 404s a module and never runs
        assertTrue("index.js imports at least one module", imported.isNotEmpty())
        assertEquals(emptyList<String>(), unserved)
        assertEquals(emptyList<String>(), absent)
    }

    @Test
    fun `the Apple badge ships as a routed static asset with an image content type`() {
        // Given - the badge artwork attribution.js references by URL
        val path = "/apple-music-badge.svg"

        // When - checking the static routes, the packaged resources, and the type it serves as
        val routed = path in STATIC_PATHS
        val packaged = ServerTest::class.java.getResourceAsStream(path) != null
        val contentType = staticContentTypeOf(path)

        // Then - the page can fetch it, and a browser renders it as an image rather than a script
        assertTrue(routed)
        assertTrue(packaged)
        assertEquals("image/svg+xml", contentType)
    }

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

    @Test
    fun `the shipped page offers its search controls ready to use`() {
        // Given - the index.html the server actually serves
        val indexHtml = checkNotNull(ServerTest::class.java.getResourceAsStream("/index.html")) { "index.html missing" }
            .readBytes()
            .decodeToString()

        // When - reading the controls a visitor's very first search goes through
        val controls = Regex("""<button\b[^>]*>""").findAll(indexHtml)
            .map { it.value }
            .filter { it.contains("""id="submit"""") || it.contains("""id="find"""") || it.contains("data-kind=") }
            .toList()

        // Then - the search box, the find button and every suggestion chip ship usable, waiting on
        // no readiness the page would have to unlock them from
        assertTrue("index.html has search controls", controls.size >= 3)
        assertEquals(emptyList<String>(), controls.filter { it.contains("disabled") })
    }
}
