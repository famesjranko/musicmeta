package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.CallMemo

/**
 * One `enrich()` call's LRCLIB track lookup, held only long enough to serve `LYRICS_SYNCED`,
 * `LYRICS_PLAIN` and `TRACK_METADATA` from one exact-then-search lookup ladder instead of one
 * each — see [LrcLibProvider.trackScope]. Dies with the call ([com.landofoz.musicmeta.engine.ProviderCallScope]),
 * so a mis-selected candidate never outlives a `forceRefresh`. A lookup that throws — a transient
 * error or the caller's own cancellation — is never written to the memo, so the next reader (in
 * this call or the next) retries it rather than being handed a false miss.
 */
internal class LrcLibTrackScope(private val api: LrcLibApi) {

    /**
     * The four fields a lookup's outcome depends on, held as distinct fields rather than one
     * delimiter-joined string: a joined key aliases legally distinct requests whenever a field's
     * own value contains the delimiter (`"A|B" / "C"` versus `"A" / "B|C"`).
     */
    private data class SelectionKey(val artist: String, val title: String, val album: String?, val durationMs: Long?)

    private val outcomes = CallMemo<SelectionKey, LrcLibOutcome>()

    suspend fun resolve(request: EnrichmentRequest.ForTrack): LrcLibOutcome {
        val key = SelectionKey(request.artist, request.title, request.album, request.durationMs)
        return outcomes.get(key) { lookup(request) }
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
        val (best, evidence) = accepted.selectBest(request) ?: return LrcLibOutcome.Miss
        return LrcLibOutcome.Found(best, exact = false, evidence = evidence)
    }
}
