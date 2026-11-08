package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.SearchCandidate

/** Scores and artwork differ by catalog; only explicit edition differences preserve a supplement. */
internal fun SearchCandidate.repeatsPrimary(primary: SearchCandidate): Boolean =
    SearchTitleMatcher.equivalent(title, primary.title) &&
        SearchTitleMatcher.foldTypography(artist.orEmpty()).trim().equals(
            SearchTitleMatcher.foldTypography(primary.artist.orEmpty()).trim(), ignoreCase = true,
        ) &&
        (year == null || primary.year == null || year == primary.year) &&
        (country.isNullOrBlank() || primary.country.isNullOrBlank() || country == primary.country) &&
        (releaseType.isNullOrBlank() || primary.releaseType.isNullOrBlank() || releaseType == primary.releaseType) &&
        disambiguation.orEmpty() == primary.disambiguation.orEmpty()
