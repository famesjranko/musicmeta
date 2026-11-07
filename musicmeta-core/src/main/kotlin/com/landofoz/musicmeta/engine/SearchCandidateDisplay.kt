package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.SearchCandidate

/** Provider and catalog identifiers do not distinguish otherwise identical visible choices. */
internal fun SearchCandidate.displayKey(): List<Any?> =
    listOf(title, artist, year, country, releaseType, thumbnailUrl, disambiguation, matchScore)
