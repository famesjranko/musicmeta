package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Puts an [EnrichmentType.ARTIST_DISCOGRAPHY] `Success` into the order
 * [EnrichmentData.Discography] documents: ascending year, with every undated album after every
 * dated one. Nothing is dropped, deduplicated, or given a substitute year, and every other type and
 * result kind is returned untouched.
 *
 * The sort is stable and keys on `year` alone, so albums sharing a year — and the whole undated
 * block — keep the order their provider gave them. Two albums released in the same year do have an
 * order, but it is the month the mapping truncated away rather than anything a second sort key
 * could recover, so ordering them on title would invent one. A provider carrying no dates at all
 * therefore has its list returned exactly as it sent it.
 */
internal fun orderDiscography(type: EnrichmentType, result: EnrichmentResult): EnrichmentResult {
    if (type != EnrichmentType.ARTIST_DISCOGRAPHY) return result
    val success = result as? EnrichmentResult.Success ?: return result
    val discography = success.data as? EnrichmentData.Discography ?: return result
    val ordered = discography.albums.sortedWith(compareBy(nullsLast()) { it.year })
    if (ordered == discography.albums) return result
    return success.copy(data = discography.copy(albums = ordered))
}
