package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher

/**
 * Ranks a `/search?entity=album` pool for [request] against `collectionName`: artist floor, then
 * [TitleMatcher.titleTier] (never `NONE`), then artist quality, then — only when the request
 * supplies the evidence — a matching [EnrichmentRequest.ForAlbum.trackCount] or a `releaseDate`
 * that starts with the requested [EnrichmentRequest.ForAlbum.year]. `maxWithOrNull` keeps the first
 * maximum, so provider order is the final tie-break for free (`docs/pitfalls.md` §7).
 *
 * Deezer's remaster-only tolerance is reused as-is: the two catalogs share the same
 * `"(2015 Remaster)"`-shaped provider decoration, not merely the comparison vocabulary.
 */
internal fun List<ITunesAlbumResult>.selectAlbum(request: EnrichmentRequest.ForAlbum): ITunesAlbumResult? =
    filter { ArtistMatcher.isMatch(request.artist, it.artistName) }
        .map { it to TitleMatcher.titleTier(request.title, it.collectionName) }
        .filter { (_, tier) -> tier != TitleMatcher.TitleTier.NONE }
        .maxWithOrNull(
            compareBy(
                { it.second },
                { ArtistMatcher.matchQuality(request.artist, it.first.artistName) },
                { request.trackCount != null && it.first.trackCount == request.trackCount },
                { request.year != null && it.first.releaseDate?.startsWith(request.year.toString()) == true },
            ),
        )
        ?.first
