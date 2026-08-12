package com.landofoz.musicmeta

/**
 * What a MusicBrainz identifier names, as answered by
 * [com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider.discoverEntityType].
 *
 * The three entity types this library can enrich, and the three
 * [EnrichmentRequest] kinds they map to: a recording is a track, a release an album.
 */
enum class MusicBrainzEntityType {
    RECORDING,
    RELEASE,
    ARTIST,
}
