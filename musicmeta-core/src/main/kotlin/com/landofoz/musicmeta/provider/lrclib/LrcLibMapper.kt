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
}
