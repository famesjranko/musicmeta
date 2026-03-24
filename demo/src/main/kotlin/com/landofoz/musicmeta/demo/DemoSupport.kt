package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.CatalogMatch
import com.landofoz.musicmeta.CatalogProvider
import com.landofoz.musicmeta.CatalogQuery
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.demo.ui.Terminal

// --- Shared constants and parsing ---

internal val BY_REGEX = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE)
internal val ARTIST_TYPES = EnrichmentRequest.DEFAULT_ARTIST_TYPES
internal val ALBUM_TYPES = EnrichmentRequest.DEFAULT_ALBUM_TYPES
internal val TRACK_TYPES = EnrichmentRequest.DEFAULT_TRACK_TYPES

/** Parses "artist <name>" / "album <title> by <artist>" / "track <title> by <artist>" into a request. */
internal fun parseEntityRequest(input: String): EnrichmentRequest? {
    val lower = input.lowercase()
    return when {
        lower.startsWith("artist ") -> {
            val name = input.substringAfter("artist ").trim()
            if (name.isBlank()) null else EnrichmentRequest.forArtist(name)
        }
        lower.startsWith("album ") -> {
            val parts = input.substringAfter("album ").trim().split(BY_REGEX, limit = 2)
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else EnrichmentRequest.forAlbum(parts[0].trim(), parts[1].trim())
        }
        lower.startsWith("track ") -> {
            val parts = input.substringAfter("track ").trim().split(BY_REGEX, limit = 2)
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            else EnrichmentRequest.forTrack(parts[0].trim(), parts[1].trim())
        }
        else -> null
    }
}

/** Entity kind label for display: "artist", "album", or "track". */
internal fun entityKind(request: EnrichmentRequest): String = when (request) {
    is EnrichmentRequest.ForArtist -> "artist"
    is EnrichmentRequest.ForAlbum -> "album"
    is EnrichmentRequest.ForTrack -> "track"
}

/** Cache wrapper that tracks hit/miss stats for demo display. */
class TrackingCache(
    private val delegate: EnrichmentCache = InMemoryEnrichmentCache(),
) : EnrichmentCache {
    var hits = 0; var misses = 0

    override suspend fun get(entityKey: String, type: EnrichmentType): EnrichmentResult.Success? =
        delegate.get(entityKey, type).also { if (it != null) hits++ else misses++ }

    override suspend fun put(entityKey: String, type: EnrichmentType, result: EnrichmentResult.Success, ttlMs: Long) =
        delegate.put(entityKey, type, result, ttlMs)

    override suspend fun invalidate(entityKey: String, type: EnrichmentType?) = delegate.invalidate(entityKey, type)
    override suspend fun isManuallySelected(entityKey: String, type: EnrichmentType) = delegate.isManuallySelected(entityKey, type)
    override suspend fun markManuallySelected(entityKey: String, type: EnrichmentType) = delegate.markManuallySelected(entityKey, type)
    override suspend fun clear() { delegate.clear(); resetStats() }
    fun resetStats() { hits = 0; misses = 0 }
}

/** Logger that bridges to Terminal output when verbose mode is enabled. */
class DemoLogger(private val term: Terminal) : EnrichmentLogger {
    var enabled = false

    override fun debug(tag: String, message: String) {
        if (enabled) term.info("[$tag] $message")
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        if (enabled) term.println(
            "  ${term.styled(term.theme.warn, term.theme.warning)} " +
                "${term.styled("[$tag]", term.theme.muted)} $message",
        )
    }
}

/** In-memory artist catalog for demonstrating catalog filtering on recommendations. */
class DemoCatalog : CatalogProvider {
    val artists = mutableSetOf<String>()

    override suspend fun checkAvailability(items: List<CatalogQuery>): List<CatalogMatch> =
        items.map { q ->
            CatalogMatch(
                available = artists.any { it.equals(q.artist, ignoreCase = true) },
                source = "demo-catalog",
            )
        }
}
