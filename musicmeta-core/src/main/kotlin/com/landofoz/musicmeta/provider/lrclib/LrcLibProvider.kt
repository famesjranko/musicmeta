package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * LRCLIB provider for synced and plain lyrics.
 * Uses the free LRCLIB API (https://lrclib.net) — no API key required.
 *
 * Lyrics are track-level only; album/artist requests return NotFound.
 */
class LrcLibProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    private val api = LrcLibApi(httpClient, rateLimiter)

    override val id: String = "lrclib"
    override val displayName: String = "LRCLIB"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.LYRICS_SYNCED,
            priority = 100,
            requiresIdentifier = false,
        ),
        ProviderCapability(
            type = EnrichmentType.LYRICS_PLAIN,
            priority = 100,
            requiresIdentifier = false,
        ),
    )

    override suspend fun enrich(
        request: EnrichmentRequest,
        type: EnrichmentType,
    ): EnrichmentResult {
        if (request !is EnrichmentRequest.ForTrack) {
            return EnrichmentResult.NotFound(type, id)
        }
        return try {
            enrichTrack(request, type)
        } catch (e: Exception) {
            EnrichmentResult.Error(type, id, e.message ?: "Unknown error", e)
        }
    }

    private suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult {
        val durationSec = request.durationMs?.let { (it / 1000).toInt() }

        // Try exact match first (with album + duration when available)
        val exactResult = api.getLyrics(
            artist = request.artist,
            track = request.title,
            album = request.album,
            durationSec = durationSec,
        )
        if (exactResult != null) {
            return toEnrichmentResult(exactResult, type, EXACT_MATCH_CONFIDENCE)
        }

        // Fall back to search
        val searchResults = api.searchLyrics(artist = request.artist, track = request.title)
        val bestMatch = searchResults.firstOrNull()
            ?: return EnrichmentResult.NotFound(type, id)

        return toEnrichmentResult(bestMatch, type, SEARCH_MATCH_CONFIDENCE)
    }

    private fun toEnrichmentResult(
        result: LrcLibResult,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult {
        val lyrics = EnrichmentData.Lyrics(
            syncedLyrics = result.syncedLyrics?.takeIf { it.isNotBlank() },
            plainLyrics = result.plainLyrics?.takeIf { it.isNotBlank() },
            isInstrumental = result.instrumental,
        )

        // If requesting synced lyrics but only plain is available, still return
        // the data — the caller can decide whether to use plain as fallback.
        if (!result.instrumental && lyrics.syncedLyrics == null && lyrics.plainLyrics == null) {
            return EnrichmentResult.NotFound(type, id)
        }

        return EnrichmentResult.Success(
            type = type,
            data = lyrics,
            provider = id,
            confidence = confidence,
        )
    }

    companion object {
        private const val EXACT_MATCH_CONFIDENCE = 0.95f
        private const val SEARCH_MATCH_CONFIDENCE = 0.7f
    }
}
