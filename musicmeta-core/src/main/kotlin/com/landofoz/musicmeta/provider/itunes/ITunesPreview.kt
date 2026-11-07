package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.engine.ArtistNameMatch
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.resolvedAliasPool
import com.landofoz.musicmeta.engine.sameNameTier
import java.net.URI

/** Preview URLs and store identifiers only; media is fetched directly by the consumer's player. */
internal suspend fun ITunesProvider.enrichPreview(request: EnrichmentRequest, api: ITunesApi): EnrichmentResult {
    val type = EnrichmentType.TRACK_PREVIEW
    if (request !is EnrichmentRequest.ForTrack) return EnrichmentResult.NotFound(type, id)
    return try {
        val constraints = PreviewConstraints.from(request) ?: return EnrichmentResult.NotFound(type, id)
        val term = listOfNotNull(request.artist, previewTitle(request.title), request.album?.takeIf { it.isNotBlank() })
            .joinToString(" ").foldPreviewApostrophes()
        val candidates = api.previewTracks(term, constraints.trackId, constraints.collectionId)
            .filter(constraints::accepts)
        val selected = candidates.selectArtist(request.artist) ?: return EnrichmentResult.NotFound(type, id)
        val track = selected.value
        EnrichmentResult.Success(
            type = type,
            data = EnrichmentData.TrackPreview(track.previewUrl, id),
            provider = id,
            confidence = ConfidenceCalculator.fuzzyMatch(hasArtistMatch = true) * selected.tier.confidenceFactor,
            resolvedIdentifiers = EnrichmentIdentifiers(extra = mapOf("itunesTrackId" to track.trackId.toString()))
                .with(IdentifierNamespace.ITUNES_COLLECTION, track.collectionId.toString()),
            provenance = if (constraints.hasIdentifier) LookupProvenance.PROVIDER_NATIVE_ID else null,
        )
    } catch (e: Exception) {
        mapError(type, e)
    }
}

private class PreviewConstraints(
    private val request: EnrichmentRequest.ForTrack,
    val trackId: Long?,
    val collectionId: Long?,
) {
    val hasIdentifier: Boolean get() = trackId != null || collectionId != null

    fun accepts(track: ITunesPreviewTrack): Boolean {
        if (track.trackId <= 0 || track.collectionId <= 0) return false
        if (trackId != null && track.trackId != trackId) return false
        if (collectionId != null && track.collectionId != collectionId) return false
        if (!TitleMatcher.equivalent(previewTitle(request.title), previewTitle(track.title))) return false
        val album = request.album
        if (!album.isNullOrBlank() &&
            !TitleMatcher.equivalent(album.foldPreviewApostrophes(), track.album.foldPreviewApostrophes())) return false
        val url = URI(track.previewUrl)
        return url.scheme == "https" && url.host != null
    }

    companion object {
        fun from(request: EnrichmentRequest.ForTrack): PreviewConstraints? {
            val track = request.identifiers.extra["itunesTrackId"]
            val album = request.identifiers.get(IdentifierNamespace.ITUNES_COLLECTION)
            if (listOf(track, album).any { it != null && (it.toLongOrNull() ?: 0) <= 0 }) return null
            return PreviewConstraints(request, track?.toLong(), album?.toLong())
        }
    }
}

private suspend fun List<ITunesPreviewTrack>.selectArtist(artist: String): ArtistNameMatch<ITunesPreviewTrack>? {
    firstOrNull { sameNameTier(artist, it.artist, emptyList()) != null }?.let {
        return ArtistNameMatch(it, checkNotNull(sameNameTier(artist, it.artist, emptyList())))
    }
    val aliases = resolvedAliasPool()
    return mapNotNull { track ->
        sameNameTier(artist, track.artist, aliases)?.let { ArtistNameMatch(track, it) }
    }.maxByOrNull { it.tier.confidenceFactor }
}

private fun String.foldPreviewApostrophes(): String = replace('‘', '\'').replace('’', '\'')

// Credit spelling is presentation; a version label remains part of the recording's identity.
private fun previewTitle(title: String): String = FEATURED_CREDIT.replace(title.foldPreviewApostrophes()) { credit ->
    if (VERSION_IN_CREDIT.containsMatchIn(credit.value)) credit.value else ""
}.trim()

private const val FEATURE_PREFIX = "(?:feat\\.?|ft\\.?|featuring)\\s+"
private val FEATURED_CREDIT = Regex(
    """\s*\($FEATURE_PREFIX[^()\[\]]+\)|\s*\[$FEATURE_PREFIX[^()\[\]]+\]""" +
        """|(?:\s+[-–—])?\s+$FEATURE_PREFIX[^()\[\]]+$""",
    RegexOption.IGNORE_CASE,
)
private val VERSION_IN_CREDIT = Regex(
    """\b(?:live|acoustic|karaoke|instrumental|remix|mix|edit|version|remaster(?:ed)?|demo|unplugged|cover|""" +
        """re[ -]?recorded|sped|slowed)\b|\s[-–—]\s""",
    RegexOption.IGNORE_CASE,
)
