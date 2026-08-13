package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.AlbumMatch
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.acceptAndRankAlbum

/**
 * Deezer's own album-title acceptance policy: [TitleMatcher.TitleTier.EXACT] when [TitleMatcher.equivalent]
 * would already accept [candidate] against [requested], [TitleMatcher.TitleTier.EDITION] when
 * [requested] is bare and [candidate]'s only qualifier is provider-added edition decoration
 * ([TitleMatcher.isEditionDecoration]), [TitleMatcher.TitleTier.NONE] otherwise. Every other candidate
 * qualifier — live, remix, deluxe, box, anniversary, soundtrack — stays identity-bearing and is
 * never admitted by a bare request. Deezer's catalogue exposes some albums only under a remaster
 * suffix (`Hunky Dory` live-returns only as `Hunky Dory (2015 Remaster)`), which is why this
 * provider tolerates the [TitleMatcher.TitleTier.EDITION] tier at all (`docs/pitfalls.md` §7).
 */
internal fun deezerAlbumTitleTier(requested: String, candidate: String): TitleMatcher.TitleTier {
    val req = TitleMatcher.parse(requested)
    val cand = TitleMatcher.parse(candidate)
    if (req.base != cand.base) return TitleMatcher.TitleTier.NONE
    if (req.qualifier == cand.qualifier) return TitleMatcher.TitleTier.EXACT
    if (req.qualifier == null && cand.qualifier != null && TitleMatcher.isEditionDecoration(cand.qualifier)) {
        return TitleMatcher.TitleTier.EDITION
    }
    return TitleMatcher.TitleTier.NONE
}

/**
 * Ranks a `/search/album` pool for [request]: artist floor, then [deezerAlbumTitleTier] (never
 * `NONE`), then artist quality, then — only when [EnrichmentRequest.ForAlbum.trackCount] is known —
 * a matching `nbTracks`. Missing `nbTracks` is unknown evidence, not agreement: it never outranks a
 * candidate whose track count is known to match, but it never loses to one either, so a request
 * with no `trackCount` hint is unaffected.
 */
internal fun List<DeezerAlbumResult>.selectAlbum(request: EnrichmentRequest.ForAlbum): AlbumMatch<DeezerAlbumResult>? =
    acceptAndRankAlbum(
        requestedArtist = request.artist,
        artistNameOf = { it.artistName },
        titleTierOf = { deezerAlbumTitleTier(request.title, it.title) },
        "trackCount" to { request.trackCount != null && it.nbTracks == request.trackCount },
    )
