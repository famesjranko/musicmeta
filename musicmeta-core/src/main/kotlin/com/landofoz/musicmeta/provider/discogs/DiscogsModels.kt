package com.landofoz.musicmeta.provider.discogs

data class DiscogsReleaseDetail(
    val id: Long,
    val title: String,
    val extraartists: List<DiscogsCredit>,
    val tracklist: List<DiscogsTrackItem>,
)

data class DiscogsCredit(
    val name: String,
    val role: String,
    val id: Long?,
)

data class DiscogsTrackItem(
    val title: String,
    val position: String,
    val extraartists: List<DiscogsCredit>,
)

data class DiscogsRelease(
    val title: String,
    val label: String?,
    val year: String?,
    val country: String?,
    val coverImage: String?,
    val releaseType: String? = null,
    val catno: String? = null,
    val genres: List<String>? = null,
    val styles: List<String>? = null,
    val releaseId: Long? = null,
    val masterId: Long? = null,
)

data class DiscogsArtist(
    val id: Long,
    val name: String,
    val members: List<DiscogsMember>,
)

data class DiscogsMember(
    val id: Long,
    val name: String,
    val active: Boolean?,
)
