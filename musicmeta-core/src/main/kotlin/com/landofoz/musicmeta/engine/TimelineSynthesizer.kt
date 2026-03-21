package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.TimelineEvent

/**
 * Pure function that synthesizes an [EnrichmentData.ArtistTimeline] from
 * sub-type enrichment results. Extracts life-span events from metadata,
 * album release events from discography, and member change events from
 * band members. Sorts chronologically and deduplicates by date+type.
 */
object TimelineSynthesizer {

    fun synthesize(
        metadata: EnrichmentResult?,
        discography: EnrichmentResult?,
        bandMembers: EnrichmentResult?,
    ): EnrichmentData.ArtistTimeline {
        val events = mutableListOf<TimelineEvent>()
        extractLifeSpanEvents(metadata, events)
        extractDiscographyEvents(discography, events)
        extractBandMemberEvents(bandMembers, events)
        val sorted = events.sortedBy { it.date }
        val deduped = sorted.distinctBy { "${it.date}:${it.type}" }
        return EnrichmentData.ArtistTimeline(deduped)
    }

    private fun extractLifeSpanEvents(
        metadata: EnrichmentResult?,
        events: MutableList<TimelineEvent>,
    ) {
        val data = (metadata as? EnrichmentResult.Success)?.data as? EnrichmentData.Metadata
            ?: return
        val isPerson = data.artistType == "Person"
        data.beginDate?.let { date ->
            val type = if (isPerson) "born" else "formed"
            val desc = if (isPerson) "Artist born" else "Band formed"
            events.add(TimelineEvent(date = date, type = type, description = desc))
        }
        data.endDate?.let { date ->
            val type = if (isPerson) "died" else "disbanded"
            val desc = if (isPerson) "Artist died" else "Band disbanded"
            events.add(TimelineEvent(date = date, type = type, description = desc))
        }
    }

    private fun extractDiscographyEvents(
        discography: EnrichmentResult?,
        events: MutableList<TimelineEvent>,
    ) {
        val data = (discography as? EnrichmentResult.Success)?.data as? EnrichmentData.Discography
            ?: return
        val albumsWithYear = data.albums.filter { it.year != null }
        if (albumsWithYear.isEmpty()) {
            data.albums.forEach { album ->
                events.add(TimelineEvent(
                    date = "",
                    type = "album_release",
                    description = album.title,
                    relatedEntity = album.title,
                    identifiers = album.identifiers,
                ))
            }
            return
        }
        val sorted = albumsWithYear.sortedBy { it.year }
        sorted.forEachIndexed { index, album ->
            val type = if (index == 0) "first_album" else "album_release"
            events.add(TimelineEvent(
                date = album.year ?: "",
                type = type,
                description = album.title,
                relatedEntity = album.title,
                identifiers = album.identifiers,
            ))
        }
    }

    private fun extractBandMemberEvents(
        bandMembers: EnrichmentResult?,
        events: MutableList<TimelineEvent>,
    ) {
        val data = (bandMembers as? EnrichmentResult.Success)?.data as? EnrichmentData.BandMembers
            ?: return
        data.members.forEach { member ->
            val period = member.activePeriod ?: return@forEach
            val parts = period.split("-")
            if (parts.isEmpty()) return@forEach
            val startYear = parts[0].trim()
            if (startYear.isNotEmpty()) {
                events.add(TimelineEvent(
                    date = startYear,
                    type = "member_joined",
                    description = "${member.name} joined",
                    relatedEntity = member.name,
                    identifiers = member.identifiers,
                ))
            }
            if (parts.size >= 2) {
                val endYear = parts[1].trim()
                if (endYear.isNotEmpty() && endYear != "present" && endYear != "?") {
                    events.add(TimelineEvent(
                        date = endYear,
                        type = "member_left",
                        description = "${member.name} left",
                        relatedEntity = member.name,
                        identifiers = member.identifiers,
                    ))
                }
            }
        }
    }
}
