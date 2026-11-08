package com.landofoz.musicmeta.provider.musicbrainz

/** The supplied-artist match and ordered hint rank that precede edition evidence for exact titles. */
internal data class MusicBrainzCandidateArtistTier(
    val suppliedArtistMatch: Boolean = false,
    val hintRank: Int = Int.MAX_VALUE,
)
