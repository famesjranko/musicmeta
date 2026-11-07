package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.EnrichDeadline
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Cross-provider popularity is discovery policy and never evidence for enrichment identity. */
internal class SearchArtistHints(private val registry: ProviderRegistry, private val logger: EnrichmentLogger) {
    suspend fun artists(request: EnrichmentRequest): List<String> {
        val title = when (request) {
            is EnrichmentRequest.ForTrack -> request.title.takeIf { request.artist.isBlank() }
            is EnrichmentRequest.ForAlbum -> request.title.takeIf { request.artist.isBlank() }
            is EnrichmentRequest.ForArtist -> null
        } ?: return emptyList()
        val itunes = registry.searchProviders().filterIsInstance<ITunesProvider>()
            .firstOrNull { it.isAvailable && registry.allowsRequest(it.id) } ?: return emptyList()
        // Leave the identity provider most of the retry budget, including on a throttled signal.
        val budget = minOf(5000L, (currentCoroutineContext()[EnrichDeadline]?.remainingMs ?: 20_000L) / 4)
        return try {
            withTimeoutOrNull(budget.coerceAtLeast(1)) {
                withContext(EnrichDeadline(budget.coerceAtLeast(1))) {
                    itunes.searchTitleArtists(title, request is EnrichmentRequest.ForAlbum)
                }
            } ?: emptyList<String>().also { logger.warn("Search", "iTunes ranking signal timed out") }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Search", "iTunes ranking signal unavailable: ${e.message}", e)
            emptyList()
        }
    }
}
