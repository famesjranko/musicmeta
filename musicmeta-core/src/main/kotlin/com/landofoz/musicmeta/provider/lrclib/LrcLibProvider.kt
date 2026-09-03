package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import kotlinx.coroutines.currentCoroutineContext

/**
 * LRCLIB provider for synced and plain lyrics.
 * Uses the free LRCLIB API (https://lrclib.net) — no API key required.
 *
 * Lyrics are track-level only; album/artist requests return NotFound.
 */
public class LrcLibProvider(
    httpClient: HttpClient,
    rateLimiter: RateLimiter,
) : EnrichmentProvider {

    private val api = LrcLibApi(httpClient, rateLimiter)

    /**
     * This call's [LrcLibTrackScope], shared by `LYRICS_SYNCED`, `LYRICS_PLAIN` and
     * `TRACK_METADATA` so no two of them search or select twice in one `enrich()` fan-out
     * ([ProviderCallScope], `docs/pitfalls.md` §12). Called directly (no engine context), each
     * call gets its own.
     */
    private suspend fun trackScope(): LrcLibTrackScope =
        currentCoroutineContext()[ProviderCallScope]?.slot(this) { LrcLibTrackScope(api) }
            ?: LrcLibTrackScope(api)

    override val id: String = "lrclib"
    override val displayName: String = "LRCLIB"
    override val requiresApiKey: Boolean = false
    override val isAvailable: Boolean = true

    override val capabilities: List<ProviderCapability> = listOf(
        ProviderCapability(
            type = EnrichmentType.LYRICS_SYNCED,
            priority = 100,
        ),
        ProviderCapability(
            type = EnrichmentType.LYRICS_PLAIN,
            priority = 100,
        ),
        ProviderCapability(
            type = EnrichmentType.TRACK_METADATA,
            priority = 40,
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
            mapError(type, e)
        }
    }

    private suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult = when (val outcome = trackScope().resolve(request)) {
        is LrcLibOutcome.Found -> toEnrichmentResult(
            outcome.result,
            type,
            if (outcome.exact) {
                ConfidenceCalculator.authoritative()
            } else {
                ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true)
            },
        )
        LrcLibOutcome.Miss -> EnrichmentResult.NotFound(type, id)
    }

    private fun toEnrichmentResult(
        result: LrcLibResult,
        type: EnrichmentType,
        confidence: Float,
    ): EnrichmentResult {
        if (type == EnrichmentType.TRACK_METADATA) {
            // An all-null payload is demoted by the engine's answers() gate (docs/pitfalls.md §8).
            return EnrichmentResult.Success(
                type = type,
                data = LrcLibMapper.toTrackMetadata(result),
                provider = id,
                confidence = confidence,
            )
        }

        val lyrics = LrcLibMapper.toLyrics(result)

        // If requesting synced lyrics but only plain is available, still return
        // the data -- the caller can decide whether to use plain as fallback.
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
}
