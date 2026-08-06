package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentData

/** Maps LRCLIB responses to EnrichmentData subclasses. */
internal object LrcLibMapper {

    fun toLyrics(result: LrcLibResult): EnrichmentData.Lyrics =
        EnrichmentData.Lyrics(
            syncedLyrics = result.syncedLyrics?.takeIf { it.isNotBlank() },
            plainLyrics = result.plainLyrics?.takeIf { it.isNotBlank() },
            isInstrumental = result.instrumental,
        )

    /** [result.duration] is seconds (LRCLIB's own unit); [EnrichmentData.TrackMetadata.durationMs] is ms. */
    fun toTrackMetadata(result: LrcLibResult): EnrichmentData.TrackMetadata =
        EnrichmentData.TrackMetadata(
            durationMs = result.duration?.takeIf { it > 0 }?.let { (it * 1000).toLong() },
            albumTitle = result.albumName?.takeIf { it.isNotBlank() },
        )
}
