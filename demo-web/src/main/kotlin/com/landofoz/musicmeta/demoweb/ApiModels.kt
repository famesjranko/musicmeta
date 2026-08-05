package com.landofoz.musicmeta.demoweb

import kotlinx.serialization.Serializable

@Serializable
data class DemoResponse(
    val kind: String,
    val name: String,
    val artist: String? = null,
    val summary: SummaryCard,
    val sections: List<Section>,
    val meta: Meta,
)

@Serializable
data class SummaryCard(
    val title: String,
    val subtitle: String? = null,
    /** When set, the frontend makes [subtitle] (the artist name on track/album pages) clickable. */
    val subtitleEnrich: EnrichTarget? = null,
    val imageUrl: String? = null,
    val text: String? = null,
    val textSource: String? = null,
    val previewTitle: String? = null,
    val previewArtist: String? = null,
    val previewAlbum: String? = null,
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
