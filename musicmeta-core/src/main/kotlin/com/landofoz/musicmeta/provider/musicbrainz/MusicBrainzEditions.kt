package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.ConfidenceCalculator

/**
 * `RELEASE_EDITIONS` — every release MusicBrainz holds in one release group.
 *
 * Apart from the rest of [MusicBrainzEnricher] because it is the one album type keyed on the
 * release-*group* identifier rather than the release identifier, so it shares neither the release
 * memo nor the chain of guards on its lookup, and needs its own copy of both for that reason: the
 * artist check ([markIfDifferentArtist]) and the year check ([markIfPredatingFirstRelease]).
 */
internal suspend fun enrichAlbumEditions(
    api: MusicBrainzApi,
    providerId: String,
    request: EnrichmentRequest.ForAlbum,
): EnrichmentResult {
    val type = EnrichmentType.RELEASE_EDITIONS
    val releaseGroupMbid = request.identifiers.musicBrainzReleaseGroupId
        ?: return EnrichmentResult.NotFound(type, providerId)
    val json = api.lookupReleaseGroup(releaseGroupMbid)
        ?: return EnrichmentResult.NotFound(type, providerId)
    // Editions are the whole answer here and there is no name route to recover by, so a
    // contradicting identifier reports and returns nothing rather than falling back.
    val credit = MusicBrainzParser.extractArtistCredit(json).orEmpty()
    if (markIfDifferentArtist(request.artist, credit)) {
        return EnrichmentResult.NotFound(type, providerId)
    }
    if (markIfPredatingFirstRelease(request.year, MusicBrainzParser.extractFirstReleaseDate(json))) {
        return EnrichmentResult.NotFound(type, providerId)
    }
    val detail = MusicBrainzCreditParser.parseReleaseGroupDetail(json)
    if (detail.releases.isEmpty()) return EnrichmentResult.NotFound(type, providerId)
    return EnrichmentResult.Success(
        type = type,
        data = MusicBrainzMapper.toReleaseEditions(detail),
        provider = providerId,
        confidence = ConfidenceCalculator.idBasedLookup(),
    )
}
