package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.engine.CallMemo

/**
 * Handles per-entity enrichment logic for MusicBrainz.
 * Called by [MusicBrainzProvider] after routing by request/type, and routing on to the artist,
 * album and track resolutions it owns.
 *
 * **One instance per call**, held for that call by
 * [com.landofoz.musicmeta.engine.ProviderCallScope] — nothing the [CallMemo]s those three hold
 * outlives it. They fold the lookups a request's types repeat into one call each, a repeat being a
 * ~1.1s wait on the shared limiter, and none needs a cap: one request's types resolve one album and
 * a handful of MBIDs.
 *
 * A memo keyed by name is keyed on the query it sends upstream, never a folded form of it: the key
 * is what claims two callers asked the same question, so a key that folds two spellings together
 * answers the second from a pool searched for the first's, and one that splits a query the search
 * sends identically pays for it twice. `trackFuzzyMemo` is the one deliberate over-split: it keys
 * on an album its fuzzy search never sends, which costs nothing while one request has one album.
 */
internal class MusicBrainzEnricher(
    api: MusicBrainzApi,
    providerId: String,
    minMatchScore: Int,
) {

    private val artists = MusicBrainzArtistEnrichment(api, providerId, minMatchScore)
    private val albums = MusicBrainzAlbumEnrichment(api, providerId, minMatchScore)
    private val tracks = MusicBrainzTrackEnrichment(api, providerId, minMatchScore)

    internal suspend fun enrichAlbum(
        request: EnrichmentRequest.ForAlbum,
        type: EnrichmentType,
    ): EnrichmentResult = albums.enrichAlbum(request, type)

    internal suspend fun enrichArtist(
        request: EnrichmentRequest.ForArtist,
        type: EnrichmentType,
    ): EnrichmentResult = artists.enrichArtist(request, type)

    internal suspend fun enrichTrack(
        request: EnrichmentRequest.ForTrack,
        type: EnrichmentType,
    ): EnrichmentResult = tracks.enrichTrack(request, type)

    /**
     * What entity [mbid] names, or null when MusicBrainz holds it under none of the three.
     *
     * **Recording, then release, then artist**, at one lookup each: 1 request when it names a
     * recording, 2 for a release, 3 for an artist and 3 when it names nothing. Recording leads
     * because that is where third-party identifiers overwhelmingly come from. Each probe reuses the
     * memo the enricher's own lookups fill, so discovery inside an `enrich()` that already looked
     * the entity up costs nothing, and a miss is paid for once per call however many types ask.
     *
     * A transient propagates rather than reading as "no such entity" — every lookup here throws it
     * through `bodyOrThrowTransient`, so absence is only ever MusicBrainz's own 404.
     */
    internal suspend fun discoverEntityType(mbid: String): MusicBrainzEntityType? = when {
        tracks.memoizedRecording(mbid) !is MusicBrainzLookup.Absent -> MusicBrainzEntityType.RECORDING
        albums.memoizedRelease(mbid) !is MusicBrainzLookup.Absent -> MusicBrainzEntityType.RELEASE
        artists.memoizedArtist(mbid) !is MusicBrainzLookup.Absent -> MusicBrainzEntityType.ARTIST
        else -> null
    }
}
