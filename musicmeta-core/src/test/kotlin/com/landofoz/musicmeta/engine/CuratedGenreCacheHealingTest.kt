package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzMapper
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzParser
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `GENRE` entry cached before genre tags carried their curated marking cannot say which of its
 * names came from a controlled vocabulary, and GENRE's TTL is 90 days. It reads as a miss so the
 * write-back heals it, the same way an entry answering nothing already does.
 */
class CuratedGenreCacheHealingTest {

    private val request = EnrichmentRequest.forArtist("Coldplay")

    private fun genreResult(provider: String, curated: Boolean?) = EnrichmentResult.Success(
        type = EnrichmentType.GENRE,
        data = EnrichmentData.Metadata(
            genres = listOf("alternative rock"),
            genreTags = listOf(GenreTag("alternative rock", 0.4f, listOf(provider), curated = curated)),
        ),
        provider = provider,
        confidence = 1.0f,
    )

    private fun engine(cache: FakeEnrichmentCache, provider: FakeProvider) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        cache,
        EnrichmentConfig(enableIdentityResolution = false),
    )

    private fun provider() = FakeProvider(
        id = "fresh",
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
    ).also { it.givenResult(EnrichmentType.GENRE, genreResult("fresh", curated = true)) }

    private suspend fun FakeEnrichmentCache.seed(result: EnrichmentResult.Success) {
        put(entityKeyFor(request, EnrichmentType.GENRE), EnrichmentType.GENRE, result, ttlMs = Long.MAX_VALUE)
    }

    @Test
    fun `a cached genre entry with no curated marking is refetched`() = runTest {
        // Given - an unexpired GENRE entry whose tags predate the curated marking
        val cache = FakeEnrichmentCache()
        cache.seed(genreResult("stale", curated = null))
        val provider = provider()

        // When - asking for GENRE
        val results = engine(cache, provider).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the provider ran and its answer, not the cached one, is returned
        assertEquals(listOf(request to EnrichmentType.GENRE), provider.enrichCalls)
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(listOf("fresh"), (success.data as EnrichmentData.Metadata).genreTags!!.single().sources)
    }

    @Test
    fun `the refetched entry replaces the unmarked one`() = runTest {
        // Given - the same unmarked entry, and a provider that now states the marking
        val cache = FakeEnrichmentCache()
        cache.seed(genreResult("stale", curated = null))

        // When - enriching once, so the write-back runs
        engine(cache, provider()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the stored entry is marked, so the next call is a hit rather than a second refetch
        val stored = cache.stored.values.filter { it.type == EnrichmentType.GENRE }
        assertTrue(stored.isNotEmpty())
        assertTrue(
            stored.all { entry ->
                (entry.data as EnrichmentData.Metadata).genreTags!!.all { it.curated != null }
            },
        )
    }

    @Test
    fun `a cached genre entry that states no curated genres is served from cache`() = runTest {
        // Given - an entry written from a real lookup for an entity whose curated genres are empty
        val cache = FakeEnrichmentCache()
        val release = MusicBrainzParser.parseLookupRelease(JSONObject(RELEASE_LOOKUP_TAGS_ONLY))!!
        cache.seed(
            EnrichmentResult.Success(
                type = EnrichmentType.GENRE,
                data = MusicBrainzMapper.toAlbumMetadata(release),
                provider = "cached",
                confidence = 1.0f,
            ),
        )
        val provider = provider()

        // When - asking for GENRE
        val results = engine(cache, provider).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - no provider call: "asked, and there are none" is an answer, not an unknown
        assertEquals(emptyList<Pair<EnrichmentRequest, EnrichmentType>>(), provider.enrichCalls)
        assertEquals("cached", (results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success).provider)
    }

    @Test
    fun `tags read off a search hit are unknown, not uncurated, so they heal`() = runTest {
        // Given - an entry mapped from a search hit, which carries no genres array to have asked with
        val cache = FakeEnrichmentCache()
        val hit = MusicBrainzParser.parseArtists(JSONObject(ARTIST_SEARCH_HIT)).single()
        cache.seed(
            EnrichmentResult.Success(
                type = EnrichmentType.GENRE,
                data = MusicBrainzMapper.toArtistMetadata(hit),
                provider = "degraded",
                confidence = 1.0f,
            ),
        )
        val provider = provider()

        // When - asking for GENRE
        engine(cache, provider).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - it refetches, rather than pinning a curation claim the degraded path never made
        assertEquals(listOf(request to EnrichmentType.GENRE), provider.enrichCalls)
    }

    @Test
    fun `an unmarked genre payload cached under another type is a miss too`() = runTest {
        // Given - the same unmarked tags cached as ALBUM_METADATA, which genreTags() falls back to
        val albumRequest = EnrichmentRequest.forAlbum("Parachutes", "Coldplay")
        val cache = FakeEnrichmentCache()
        cache.put(
            entityKeyFor(albumRequest, EnrichmentType.ALBUM_METADATA),
            EnrichmentType.ALBUM_METADATA,
            genreResult("stale", curated = null).copy(type = EnrichmentType.ALBUM_METADATA),
            ttlMs = Long.MAX_VALUE,
        )
        val provider = FakeProvider(
            id = "fresh",
            capabilities = listOf(ProviderCapability(EnrichmentType.ALBUM_METADATA, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.ALBUM_METADATA,
                genreResult("fresh", curated = true).copy(type = EnrichmentType.ALBUM_METADATA),
            )
        }

        // When - asking only for ALBUM_METADATA
        engine(cache, provider).enrich(albumRequest, setOf(EnrichmentType.ALBUM_METADATA))

        // Then - the entry heals here as well, since it is the payload and not the type that is poorer
        assertEquals(listOf(albumRequest to EnrichmentType.ALBUM_METADATA), provider.enrichCalls)
    }

    @Test
    fun `a cached payload carrying no genre tags is left alone`() = runTest {
        // Given - a LABEL entry, whose Metadata has no genreTags to be unmarked
        val albumRequest = EnrichmentRequest.forAlbum("Parachutes", "Coldplay")
        val cached = EnrichmentResult.Success(
            type = EnrichmentType.LABEL,
            data = EnrichmentData.Metadata(label = "Parlophone"),
            provider = "cached",
            confidence = 1.0f,
        )
        val cache = FakeEnrichmentCache()
        cache.put(
            entityKeyFor(albumRequest, EnrichmentType.LABEL), EnrichmentType.LABEL, cached, Long.MAX_VALUE,
        )
        val provider = FakeProvider(
            id = "fresh",
            capabilities = listOf(ProviderCapability(EnrichmentType.LABEL, 100)),
        )

        // When - asking for LABEL
        val results = engine(cache, provider).enrich(albumRequest, setOf(EnrichmentType.LABEL))

        // Then - served from cache: widening the predicate past genre tags would reopen every type
        assertEquals(emptyList<Pair<EnrichmentRequest, EnrichmentType>>(), provider.enrichCalls)
        assertEquals("cached", (results.raw[EnrichmentType.LABEL] as EnrichmentResult.Success).provider)
    }

    private companion object {
        // captured 2026-08-12: GET /release/4ebc64a2-e9cc-43f8-96ac-536212c4c8d4
        // ?inc=...+tags+genres — trimmed; a release's own tags and genres are both empty, and this
        // one is trimmed further to a tag-only release group so the lookup answers "asked, none"
        const val RELEASE_LOOKUP_TAGS_ONLY = """
        {
          "id": "4ebc64a2-e9cc-43f8-96ac-536212c4c8d4",
          "title": "A Rush of Blood to the Head",
          "tags": [ { "count": 1, "name": "sad" } ],
          "genres": []
        }
        """

        // captured 2026-08-12: GET /artist?query=artist:"Cold Play" OR alias:"Cold Play"&limit=5 —
        // trimmed; a search hit carries tags with counts and no genres array at all
        const val ARTIST_SEARCH_HIT = """
        {
          "count": 1,
          "artists": [
            {
              "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234",
              "name": "Coldplay",
              "type": "Group",
              "score": 100,
              "country": "GB",
              "tags": [ { "count": 34, "name": "alternative rock" } ]
            }
          ]
        }
        """
    }
}
