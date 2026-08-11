package com.landofoz.musicmeta.provider.musicbrainz

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicBrainz publishes two genre surfaces for the same entity: `genres`, the controlled vocabulary
 * an editor accepted, and `tags`, everything anyone typed. A result has to keep them apart.
 */
class MusicBrainzCuratedGenreTest {

    @Test
    fun `a release lookup reads its release group's curated genres`() {
        // Given - a release whose own genres are empty and whose release group carries them
        val json = JSONObject(RELEASE_LOOKUP_RUSH_OF_BLOOD)

        // When - parsing the lookup response
        val release = MusicBrainzParser.parseLookupRelease(json)!!

        // Then - the curated names come off the release group, as the community tags already do
        assertEquals(listOf("alternative rock", "pop rock", "post-britpop"), release.genreCounts.map { it.name })
    }

    @Test
    fun `the curated vocabulary excludes the community tags that are not genres`() {
        // Given - the same release, whose release group is tagged "sad" and "alt rock"
        val release = MusicBrainzParser.parseLookupRelease(JSONObject(RELEASE_LOOKUP_RUSH_OF_BLOOD))!!

        // When - comparing the two surfaces
        val curated = release.genreCounts.map { it.name }
        val community = release.tagCounts.map { it.name }

        // Then - the noise is present as a tag and absent from the curated list
        assertTrue(community.containsAll(listOf("sad", "alt rock")))
        assertFalse(curated.contains("sad"))
        assertFalse(curated.contains("alt rock"))
    }

    @Test
    fun `curated genres are marked and ranked ahead of community tags`() {
        // Given - a release carrying both surfaces
        val release = MusicBrainzParser.parseLookupRelease(JSONObject(RELEASE_LOOKUP_RUSH_OF_BLOOD))!!

        // When - mapping it to album metadata
        val genreTags = MusicBrainzMapper.toAlbumMetadata(release).genreTags!!

        // Then - every curated name leads, marked as such and above a tag's confidence
        val curatedCount = release.genreCounts.size
        assertEquals(
            release.genreCounts.map { it.name },
            genreTags.take(curatedCount).map { it.name },
        )
        assertTrue(genreTags.take(curatedCount).all { it.curated == true && it.confidence == 0.7f })
        assertTrue(genreTags.drop(curatedCount).all { it.curated == false && it.confidence == 0.4f })
    }

    @Test
    fun `a curated genre is not repeated as a community tag`() {
        // Given - a release whose curated genres are a subset of its tags, as MusicBrainz sends them
        val release = MusicBrainzParser.parseLookupRelease(JSONObject(RELEASE_LOOKUP_RUSH_OF_BLOOD))!!

        // When - mapping it to album metadata
        val names = MusicBrainzMapper.toAlbumMetadata(release).genreTags!!.map { it.name }

        // Then - each name appears once
        assertEquals(names.distinct(), names)
    }

    @Test
    fun `an entity with no curated genres still states that its tags are not curated`() {
        // Given - a response carrying tags and no genres array at all
        val release = MusicBrainzParser.parseLookupRelease(JSONObject(RELEASE_LOOKUP_NO_GENRES))!!

        // When - mapping it to album metadata
        val genreTags = MusicBrainzMapper.toAlbumMetadata(release).genreTags!!

        // Then - curated is false rather than null, so the entry never reads as a pre-marking one
        assertTrue(genreTags.all { it.curated == false })
    }

    @Test
    fun `the plain genres list follows the same curated-first order`() {
        // Given - a release carrying both surfaces
        val release = MusicBrainzParser.parseLookupRelease(JSONObject(RELEASE_LOOKUP_RUSH_OF_BLOOD))!!

        // When - mapping it to album metadata
        val metadata = MusicBrainzMapper.toAlbumMetadata(release)

        // Then - the untyped list a consumer reads first agrees with the typed one
        assertEquals(metadata.genreTags!!.map { it.name }, metadata.genres)
    }

    private companion object {
        // captured 2026-08-12: GET /release/4ebc64a2-e9cc-43f8-96ac-536212c4c8d4
        // ?inc=artist-credits+labels+release-groups+tags+genres+media+recordings — trimmed to three
        // curated genres, the tags they came from, and the tags the vocabulary rejects
        const val RELEASE_LOOKUP_RUSH_OF_BLOOD = """
        {
          "id": "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4",
          "title": "A Rush of Blood to the Head",
          "date": "2002-08-26",
          "country": "GB",
          "status": "Official",
          "barcode": "724354057502",
          "tags": [],
          "release-group": {
            "id": "1a5e4b0a-0e6d-3b4a-9f18-1d3f5c5c5f0a",
            "title": "A Rush of Blood to the Head",
            "primary-type": "Album",
            "first-release-date": "2002-08-26",
            "disambiguation": "",
            "secondary-types": [],
            "tags": [
              { "count": 3, "name": "alt rock" },
              { "count": 16, "name": "alternative rock" },
              { "count": 9, "name": "pop rock" },
              { "count": 3, "name": "post-britpop" },
              { "count": 1, "name": "sad" }
            ],
            "genres": [
              { "count": 16, "name": "alternative rock", "id": "ceeaa283-5d7b-4202-8d1d-e25d116b2a18", "disambiguation": "" },
              { "count": 9, "name": "pop rock", "id": "797e2e85-5ffd-495c-a757-8b4079052f0e", "disambiguation": "" },
              { "count": 3, "name": "post-britpop", "id": "a493b79c-4072-4371-a14c-446b1027982a", "disambiguation": "" }
            ]
          }
        }
        """

        // synthetic: the same shape with the genres array absent, which is what a release nobody has
        // filed a genre against returns
        const val RELEASE_LOOKUP_NO_GENRES = """
        {
          "id": "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4",
          "title": "A Rush of Blood to the Head",
          "tags": [ { "count": 1, "name": "sad" } ]
        }
        """
    }
}
