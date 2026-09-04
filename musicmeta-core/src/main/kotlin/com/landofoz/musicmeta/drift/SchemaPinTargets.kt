package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveApi
import com.landofoz.musicmeta.provider.deezer.DeezerApi
import com.landofoz.musicmeta.provider.discogs.DiscogsApi
import com.landofoz.musicmeta.provider.fanarttv.FanartTvApi
import com.landofoz.musicmeta.provider.itunes.ITunesApi
import com.landofoz.musicmeta.provider.lastfm.LastFmApi
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzApi
import com.landofoz.musicmeta.provider.lrclib.LrcLibApi
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi
import com.landofoz.musicmeta.provider.wikidata.WikidataApi
import com.landofoz.musicmeta.provider.wikipedia.WikipediaApi

/**
 * Every route the schema pin watches, one list per provider, assembled here.
 *
 * The three keyed providers take their credential as an argument rather than reading it: a target
 * list that resolves a key on its own would be a static holding a secret, and this list is built
 * once per run by the job that already has them in scope.
 *
 * A provider added without a target list is caught by `scripts/checks/check_schema_pin_coverage.py`
 * rather than here — an omission from this file would otherwise read as "that provider has no
 * routes worth watching", which is not a claim a missing import can make.
 */
internal fun allSchemaPinTargets(
    lastFmApiKey: String,
    fanartTvApiKey: String,
    discogsToken: String,
): List<SchemaTarget> =
    CoverArtArchiveApi.SCHEMA_PIN_TARGETS +
        DeezerApi.SCHEMA_PIN_TARGETS +
        ITunesApi.SCHEMA_PIN_TARGETS +
        ListenBrainzApi.SCHEMA_PIN_TARGETS +
        LrcLibApi.SCHEMA_PIN_TARGETS +
        MusicBrainzApi.SCHEMA_PIN_TARGETS +
        WikidataApi.SCHEMA_PIN_TARGETS +
        WikipediaApi.SCHEMA_PIN_TARGETS +
        LastFmApi.schemaPinTargets(lastFmApiKey) +
        FanartTvApi.schemaPinTargets(fanartTvApiKey) +
        DiscogsApi.schemaPinTargets(discogsToken)
