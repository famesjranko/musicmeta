package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One `enrich()` call's LRCLIB track lookup, held only long enough to serve `LYRICS_SYNCED`,
 * `LYRICS_PLAIN` and `TRACK_METADATA` from one exact-then-search lookup ladder instead of one
 * each — see [LrcLibProvider.trackScope]. Dies with the call ([com.landofoz.musicmeta.engine.ProviderCallScope]),
 * so a mis-selected candidate never outlives a `forceRefresh`. A lookup that throws — a transient
 * error or the caller's own cancellation — is never written to the memo, so the next reader (in
 * this call or the next) retries it rather than being handed a false miss.
 */
internal class LrcLibTrackScope(private val api: LrcLibApi) {

    private val mutex = Mutex()
    private val outcomes = mutableMapOf<String, LrcLibOutcome>()

    suspend fun resolve(request: EnrichmentRequest.ForTrack): LrcLibOutcome = mutex.withLock {
        val key = "${request.artist}|${request.title}|${request.album}|${request.durationMs}"
        outcomes[key] ?: lookup(request).also { outcomes[key] = it }
    }

    private suspend fun lookup(request: EnrichmentRequest.ForTrack): LrcLibOutcome {
        val durationSec = request.durationMs?.let { it / 1000.0 }
        val exact = api.getLyrics(
            artist = request.artist,
            track = request.title,
            album = request.album,
            durationSec = durationSec,
        )
        if (exact != null) return LrcLibOutcome.Found(exact, exact = true)

        val accepted = api.searchLyrics(artist = request.artist, track = request.title)
            .filter { LrcLibAcceptance.accepts(request, it) }
        val best = accepted.selectBest(request) ?: return LrcLibOutcome.Miss
        return LrcLibOutcome.Found(best, exact = false)
    }
}
