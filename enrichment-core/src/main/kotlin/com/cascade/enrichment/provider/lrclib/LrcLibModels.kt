package com.cascade.enrichment.provider.lrclib

/**
 * Response model from the LRCLIB API.
 * Represents a single lyrics result for a track.
 */
data class LrcLibResult(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val duration: Double?,
    val instrumental: Boolean,
    val syncedLyrics: String?,
    val plainLyrics: String?,
)
