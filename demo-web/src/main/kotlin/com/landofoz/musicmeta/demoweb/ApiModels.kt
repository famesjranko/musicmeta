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
    /** False unless identity resolved (or needed no resolution) — do not present [title] as a match. */
    val identityResolved: Boolean = true,
    /**
     * The bare `IdentityMatch` enum name to branch on, or `null` when resolution was skipped —
     * unlike [Meta.identityMatch], which is a display string. Section presence is not a substitute:
     * a `SUGGESTIONS` verdict whose candidates all filtered out looks just like `BEST_EFFORT`.
     */
    val identityVerdict: String? = null,
    val genres: List<GenreChip> = emptyList(),
)

@Serializable
data class GenreChip(
    val name: String,
    /** null means the source predates the curated marking — render as community, badge nothing. */
    val curated: Boolean? = null,
    val confidence: Float,
    val sources: List<String> = emptyList(),
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
    /**
     * What identity resolution resolved this entity to, in the order a reader wants them: the
     * entity's own MBID first, then the ids other providers were keyed on. Empty when resolution
     * was skipped or found nothing — an entity the library could not pin has none to show.
     */
    val identifiers: List<IdentifierHit> = emptyList(),
)

/**
 * [label] is which identifier this is, and it is decided by the entity being rendered rather than by
 * the field it came from: `EnrichmentIdentifiers.musicBrainzId` is a recording id on a track, a
 * release id on an album and an artist id on an artist, and an unlabelled MBID is not one a reader
 * can act on — MusicBrainz has no endpoint that takes one without knowing its type.
 */
@Serializable
data class IdentifierHit(
    val label: String,
    val value: String,
)

@Serializable
data class ProviderHit(
    val type: String,
    val provider: String,
    val status: String,
    val confidence: Float? = null,
)

@Serializable
data class ProvidersResponse(val providers: List<ProviderRow>)

/**
 * One provider this instance was built with, as `getProviders()` reports it, joined to the terms
 * snapshot `ProviderPolicies` ships for it.
 *
 * [available] is the provider's own availability — false for a keyed provider with no key, which is
 * why [requiresApiKey] is rendered beside it rather than inferred from it.
 */
@Serializable
data class ProviderRow(
    val id: String,
    val displayName: String,
    val available: Boolean,
    val requiresApiKey: Boolean,
    /** Bare `EnrichmentType` names, one per declared capability. */
    val capabilities: List<String> = emptyList(),
    /** null when musicmeta records no terms snapshot for this provider — not "no obligations". */
    val policy: PolicyRow? = null,
)

/** The renderable subset of a `ProviderPolicy`: enum fields as bare enum names, notice text as-is. */
@Serializable
data class PolicyRow(
    val commercialUse: String,
    /** null means the licence could not be verified on the snapshot's date. */
    val dataLicence: String? = null,
    val attribution: String,
    /** Ready-to-render text; null when no notice is owed or none could be read. */
    val attributionNotice: String? = null,
)

@Serializable
data class ApiError(val error: String)

@Serializable
data class InvalidateRequest(
    val kind: String,
    val name: String = "",
    val artist: String? = null,
    val album: String? = null,
    val mbid: String? = null,
)

@Serializable
data class InvalidateResponse(val invalidated: Boolean)
