package com.landofoz.musicmeta

import kotlinx.serialization.Serializable

/** Source facts a caller needs to credit and assess reuse of one text or media resource. */
@Serializable
public data class ContentAttribution(
    /** Canonical article title or file title, including `File:` for media. */
    val resourceId: String,
    /** Description page for media, or article page for text. */
    val sourceUrl: String,
    val creator: String? = null,
    val credit: String? = null,
    /** Source-required credit that supersedes a constructed creator-and-credit sentence. */
    val attributionText: String? = null,
    /** Licence designations reported by the source; unfamiliar designations are retained verbatim. */
    val licenses: List<ContentLicense> = emptyList(),
    /** How [licenses] combine; [LicenseRelation.UNKNOWN] makes no reuse claim. */
    val licenseRelation: LicenseRelation = LicenseRelation.UNKNOWN,
    /** Whether the source explicitly reports copyright. `false` may report public-domain material. */
    val copyrighted: Boolean? = null,
    val attributionRequired: Boolean? = null,
    val nonFree: Boolean? = null,
    val usageTerms: String? = null,
    /** `null` means unreported; an empty list means the source explicitly reported no restrictions. */
    val restrictions: List<String>? = null,
    /** `null` means the source did not report whether the resource was modified. */
    val isModified: Boolean? = null,
    val modificationNote: String? = null,
)

/** A licence designation reported by a source, with its source-supplied reference URL when present. */
@Serializable
public data class ContentLicense(
    val identifier: String,
    val url: String? = null,
)

/** The relationship among licences reported for a resource. */
@Serializable
public enum class LicenseRelation {
    /** The source did not establish how licences combine. */
    UNKNOWN,

    /** A consumer may choose one reported licence. */
    ANY_OF,

    /** A consumer must satisfy every reported licence. */
    ALL_OF,
}
