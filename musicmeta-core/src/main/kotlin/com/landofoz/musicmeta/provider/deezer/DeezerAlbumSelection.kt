package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher

/**
 * Ranks a `/search/album` pool for [request], keeping only candidates that pass the artist floor
 * and clear [TitleMatcher.titleTier]'s `NONE` tier, then picking the survivor with the highest
 * title tier, then artist quality, then — only when [EnrichmentRequest.ForAlbum.trackCount] is
 * known — a matching `nbTracks`. `maxWithOrNull` keeps the first maximum, so provider order is the
 * final tie-break for free, the same convention `bestArtistMatch` uses (`docs/pitfalls.md` §7).
 *
 * Missing `nbTracks` is unknown evidence, not agreement: it never outranks a candidate whose track
 * count is known to match, but it never loses to one either, so a request with no `trackCount`
 * hint is unaffected.
 */
internal fun List<DeezerAlbumResult>.selectAlbum(request: EnrichmentRequest.ForAlbum): DeezerAlbumResult? =
    filter { ArtistMatcher.isMatch(request.artist, it.artistName) }
        .map { it to TitleMatcher.titleTier(request.title, it.title) }
        .filter { (_, tier) -> tier != TitleMatcher.TitleTier.NONE }
        .maxWithOrNull(
            compareBy(
                { it.second },
                { ArtistMatcher.matchQuality(request.artist, it.first.artistName) },
                { request.trackCount != null && it.first.nbTracks == request.trackCount },
            ),
        )
        ?.first
