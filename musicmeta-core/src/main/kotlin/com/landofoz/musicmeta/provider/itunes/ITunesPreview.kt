package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.ArtistNameMatch
import com.landofoz.musicmeta.engine.ConfidenceCalculator
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.resolvedAliasPool
import com.landofoz.musicmeta.engine.sameNameTier
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzQualifierFallback
import java.net.URI
import java.net.URISyntaxException

/** Preview URLs and store identifiers only; media is fetched directly by the consumer's player. */
internal suspend fun ITunesProvider.enrichPreview(request: EnrichmentRequest, api: ITunesApi): EnrichmentResult {
    val type = EnrichmentType.TRACK_PREVIEW
    if (request !is EnrichmentRequest.ForTrack) return EnrichmentResult.NotFound(type, id)
    return try {
        val constraints = PreviewConstraints.from(request) ?: return EnrichmentResult.NotFound(type, id)
        val baseTerm = "${request.artist} ${previewTitle(request.title)}".foldPreviewApostrophes()
        val album = request.album?.takeIf { it.isNotBlank() }
        val term = listOfNotNull(baseTerm, album?.foldPreviewApostrophes()).joinToString(" ")
        val selected = constraints.select(api.previewTracks(term, constraints.trackId, constraints.collectionId))
            ?: baseTerm.takeIf { album != null && !constraints.hasIdentifier }?.let {
                constraints.select(api.previewTracks(it, null, null))
            }
            ?: return EnrichmentResult.NotFound(type, id)
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
    private val title = parsePreviewTitle(request.title)
    private val album = request.album?.takeIf { it.isNotBlank() }?.foldPreviewApostrophes()
    private val albumTitles = album?.let(::previewAlbumTitles).orEmpty()

    val hasIdentifier: Boolean get() = trackId != null || collectionId != null

    fun accepts(track: ITunesPreviewTrack): Boolean {
        if (track.trackId <= 0 || track.collectionId <= 0) return false
        if (trackId != null && track.trackId != trackId) return false
        if (collectionId != null && track.collectionId != collectionId) return false
        if (!title.matches(parsePreviewTitle(track.title))) return false
        val url = try {
            URI(track.previewUrl)
        } catch (_: URISyntaxException) {
            return false
        }
        return url.scheme == "https" && url.host != null
    }

    suspend fun select(candidates: List<ITunesPreviewTrack>): ArtistNameMatch<ITunesPreviewTrack>? =
        candidates.filter(::accepts).groupBy(::titlePreference).toSortedMap(reverseOrder())
            .values.firstNotNullOfOrNull { it.selectArtist(request.artist, ::albumPreference) }

    private fun albumPreference(track: ITunesPreviewTrack): TitleMatcher.TitleTier = when {
        album != null && TitleMatcher.equivalent(album, track.album.foldPreviewApostrophes()) ->
            TitleMatcher.TitleTier.EXACT
        albumTitles.any { it in previewAlbumTitles(track.album) } -> TitleMatcher.TitleTier.EDITION
        else -> TitleMatcher.TitleTier.NONE
    }

    private fun titlePreference(track: ITunesPreviewTrack): Int = when {
        TitleMatcher.equivalent(request.title.foldPreviewApostrophes(), track.title.foldPreviewApostrophes()) -> 2
        title.credits.isNotEmpty() && parsePreviewTitle(track.title).credits.isNotEmpty() -> 1
        else -> 0
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

private suspend fun List<ITunesPreviewTrack>.selectArtist(
    artist: String,
    albumPreference: (ITunesPreviewTrack) -> TitleMatcher.TitleTier,
): ArtistNameMatch<ITunesPreviewTrack>? {
    fun matches(aliases: List<AlternativeName>) = mapNotNull { track ->
        sameNameTier(artist, track.artist, aliases)?.let { ArtistNameMatch(track, it) }
    }
    val matches = matches(emptyList()).ifEmpty { matches(resolvedAliasPool()) }
    val selected = matches.maxWithOrNull(compareBy<ArtistNameMatch<ITunesPreviewTrack>> { albumPreference(it.value) }
        .thenBy { it.tier.confidenceFactor }) ?: return null
    val title = parsePreviewTitle(selected.value.title)
    if (matches.any { !title.sameCredits(parsePreviewTitle(it.value.title)) }) return null
    return selected
}

private fun previewAlbumTitles(album: String): List<TitleMatcher.Parts> =
    MusicBrainzQualifierFallback.qualifierFallbackCandidates(album.foldPreviewApostrophes())
        .map { TitleMatcher.parse(it.title) }

private fun String.foldPreviewApostrophes(): String = replace('‘', '\'').replace('’', '\'')

private data class PreviewTitle(val base: String, val credits: List<String>) {
    fun matches(other: PreviewTitle): Boolean {
        if (!TitleMatcher.equivalent(base, other.base)) return false
        return credits.isEmpty() || sameCredits(other)
    }

    fun sameCredits(other: PreviewTitle): Boolean =
        credits.size == other.credits.size && credits.zip(other.credits).all { (a, b) ->
            sameNameTier(a, b, emptyList()) != null
        }
}

private fun previewTitle(title: String): String = parsePreviewTitle(title).base

// Parse bracketed credits separately so requested guests remain recording constraints.
private fun parsePreviewTitle(title: String): PreviewTitle {
    val folded = title.foldPreviewApostrophes()
    val credits = mutableListOf<String>()
    val base = FEATURED_CREDIT.replace(folded) { credit ->
        if (VERSION_IN_CREDIT.containsMatchIn(credit.value)) {
            credit.value
        } else {
            credits.add(checkNotNull(credit.groups[1] ?: credit.groups[2]).value.trim())
            ""
        }
    }.trim()
    return if (base.isEmpty()) PreviewTitle(folded.trim(), emptyList()) else PreviewTitle(base, credits)
}

private const val FEATURE_PREFIX = "(?:feat\\.?|ft\\.?|featuring)\\s+"
private val FEATURED_CREDIT = Regex(
    """\s*\($FEATURE_PREFIX([^()\[\]]+)\)|\s*\[$FEATURE_PREFIX([^()\[\]]+)\]""",
    RegexOption.IGNORE_CASE,
)
private val VERSION_IN_CREDIT = Regex(
    """\b(?:live|acoustic|karaoke|instrumental|remix|mix|edit|version|remaster(?:ed)?|demo|unplugged|cover|""" +
        """re[ -]?recorded|sped|slowed)\b|\s[-–—]\s""",
    RegexOption.IGNORE_CASE,
)
