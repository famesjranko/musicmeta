package com.landofoz.musicmeta

import kotlinx.serialization.Serializable

/**
 * What an upstream says about where a piece of content came from and on what terms it is offered:
 * who made it, where it is described, and which licence it names.
 *
 * **Descriptive data, and the library acts on none of it.** No field here decides whether content
 * is returned, cached, served stale, merged, ranked or refetched, and no field states that a reuse
 * is allowed. A payload whose [Attribution] is absent, partial, restrictive or self-contradictory
 * is returned exactly like one whose attribution is complete. Whether to show the content, how to
 * credit it and whether to link anything is the consumer's decision.
 *
 * Every field is optional, because an upstream rarely states all of them and a partial answer is
 * kept rather than discarded. Each value is the upstream's own statement, passed through as text:
 * a URL here is not checked for scheme or safety, so validate before rendering it as a link, and
 * escape text before placing it in markup as you would any other untrusted string.
 */
@Serializable
public data class Attribution(
    /** The work's name as the upstream gives it: an article title, or a file's title. */
    val title: String? = null,
    /** Language of [title], as a language tag (`en`). */
    val language: String? = null,
    /** The page that describes the work: an article, or a media file's description page. */
    val sourceUrl: String? = null,
    /** Who made the work, as the upstream names them. Free text, which may contain markup. */
    val creator: String? = null,
    /**
     * Credit text the upstream asks a reuse to carry. Where an upstream supplies it, it is that
     * upstream's own preferred credit in place of [creator] and [credit]; all three are kept.
     */
    val attributionText: String? = null,
    /** Where the upstream says the work was obtained, or its credit line. */
    val credit: String? = null,
    /** The licence as the upstream names it, such as `CC BY-SA 4.0` or `Public domain`. */
    val licence: String? = null,
    /** The upstream's page for [licence], when it gives one. */
    val licenceUrl: String? = null,
    /**
     * Every further licence the upstream names for the same work. When it is not empty the
     * upstream named several, and this field does not say whether they are alternatives or apply
     * together.
     */
    val otherLicences: List<String> = emptyList(),
    /** The upstream's copyright statement for the work, such as `True`, `False` or `Public domain`. */
    val copyrightStatus: String? = null,
    /** Whether and how the work was changed from the original, in the upstream's words. */
    val modification: String? = null,
    /** Reuse restrictions the upstream lists, each in its own words. */
    val restrictions: List<String> = emptyList(),
)
