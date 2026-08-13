package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.AlbumMatch
import com.landofoz.musicmeta.engine.AlbumTieBreak
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.acceptAndRankAlbum

/**
 * iTunes's own album-title acceptance policy, adjudicated separately from Deezer's: [TitleMatcher.TitleTier.EXACT]
 * when [TitleMatcher.equivalent] would already accept [candidate] against [requested],
 * [TitleMatcher.TitleTier.EDITION] when [requested] is bare and [candidate]'s only qualifier is
 * provider-added edition decoration ([TitleMatcher.isEditionDecoration]), [TitleMatcher.TitleTier.NONE]
 * otherwise. Live `collectionName` search results are decorated the same way Deezer's are —
 * `Hunky Dory` live-returns only as `Hunky Dory (2015 Remaster)` — so this tier is tolerated on the
 * same evidence, not copied from Deezer's code. Live, remix, deluxe, box, anniversary, and
 * soundtrack qualifiers stay identity-bearing and are never admitted by a bare request
 * (`docs/pitfalls.md` §7).
 */
internal fun itunesAlbumTitleTier(requested: String, candidate: String): TitleMatcher.TitleTier {
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
 * Ranks a `/search?entity=album` pool for [request] against `collectionName`: artist floor, then
 * [itunesAlbumTitleTier] (never `NONE`), then artist quality, then — only when the request supplies
 * the evidence — a matching [EnrichmentRequest.ForAlbum.trackCount] or a `releaseDate` that starts
 * with the requested [EnrichmentRequest.ForAlbum.year].
 */
internal fun List<ITunesAlbumResult>.selectAlbum(request: EnrichmentRequest.ForAlbum): AlbumMatch<ITunesAlbumResult>? =
    acceptAndRankAlbum(
        requestedArtist = request.artist,
        artistNameOf = { it.artistName },
        titleTierOf = { itunesAlbumTitleTier(request.title, it.collectionName) },
        AlbumTieBreak("trackCount") { request.trackCount != null && it.trackCount == request.trackCount },
        AlbumTieBreak("year") { request.year != null && it.releaseDate?.startsWith(request.year.toString()) == true },
    )
