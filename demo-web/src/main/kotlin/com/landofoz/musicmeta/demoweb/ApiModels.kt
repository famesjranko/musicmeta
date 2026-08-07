package com.landofoz.musicmeta.demoweb

import kotlinx.serialization.Serializable

@Serializable
data class DemoResponse(
    val kind: String,
    val name: String,
    val artist: String? = null,
    val summary: SummaryCard,
    val sections: List<Section>,
    /** Small image strip (album art extras, artist logo/banner) — absent when nothing resolved. */
    val gallery: List<GalleryImage> = emptyList(),
    val meta: Meta,
)

@Serializable
data class SummaryCard(
    val title: String,
    val subtitle: String? = null,
    /** When set, the frontend makes [subtitle] (the artist name on track/album pages) clickable. */
    val subtitleEnrich: EnrichTarget? = null,
    val imageUrl: String? = null,
    /** Artist background artwork, rendered as a dimmed backdrop behind the summary card. */
    val backgroundImageUrl: String? = null,
    val text: String? = null,
    val textSource: String? = null,
    val previewTitle: String? = null,
    val previewArtist: String? = null,
    val previewAlbum: String? = null,
    /** False when identity resolution did not land on [com.landofoz.musicmeta.IdentityMatch.RESOLVED]
     * (or `null`, which needs no resolution) — the frontend must not present [title] as a confident match. */
    val identityResolved: Boolean = true,
    /**
     * The [com.landofoz.musicmeta.IdentityMatch] enum name, or `null` when identity resolution was
     * skipped. Carries the actual verdict so the frontend doesn't have to infer it from section
     * presence — a `SUGGESTIONS` verdict with no usable suggestions still omits the "Did You Mean?"
     * section, which would otherwise look identical to `BEST_EFFORT`.
     *
     * Distinct from [Meta.identityMatch], which is a human-readable summary for display; this is
     * the bare enum name the frontend compares against.
     */
    val identityVerdict: String? = null,
)

@Serializable
data class GalleryImage(
    val url: String,
    val label: String? = null,
)

@Serializable
data class Section(
    val key: String,
    val label: String,
    val items: List<SectionItem>,
)

@Serializable
data class SectionItem(
    val primary: String,
    val secondary: String? = null,
    val imageUrl: String? = null,
    val meta: String? = null,
    /** External URL — when present, the frontend renders [primary] as a link. */
    val link: String? = null,
    /**
     * Internal navigation target — when present, the frontend makes the item (or, if [link] is
     * also set, the row rather than [primary]) clickable to re-run enrichment for this item in
     * place, distinct from [link]'s external navigation.
     */
    val enrich: EnrichTarget? = null,
    /** When set, the frontend shows a play button that resolves a 30s preview on demand. */
    val previewTitle: String? = null,
    val previewArtist: String? = null,
    val previewAlbum: String? = null,
)

/** An in-app enrichment lookup a click can run — the internal counterpart to [SectionItem.link]. */
@Serializable
data class EnrichTarget(
    val kind: String,
    val name: String,
    val artist: String? = null,
    val album: String? = null,
)

@Serializable
data class PreviewResponse(
    val url: String? = null,
    val durationMs: Long? = null,
    val source: String? = null,
)

@Serializable
data class Meta(
    val elapsedMs: Long,
    val identityMatch: String? = null,
    val providers: List<ProviderHit>,
)

@Serializable
data class ProviderHit(
    val type: String,
    val provider: String,
    val status: String,
    val confidence: Float? = null,
)

@Serializable
data class ApiError(val error: String)
