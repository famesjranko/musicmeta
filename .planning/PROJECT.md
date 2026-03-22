# musicmeta

## What This Is

A pure Kotlin/JVM music metadata enrichment and recommendation library that aggregates data from 11 public APIs (MusicBrainz, Last.fm, Wikidata, Wikipedia, Cover Art Archive, Fanart.tv, Deezer, iTunes, Discogs, ListenBrainz, LRCLIB) into a unified pipeline with priority chains, circuit breakers, rate limiting, and identity resolution. 31 enrichment types across 8 categories: artwork (7 types), metadata (9 types), text (3 types), relationships (3 types), statistics (2 types), links (2 types), composite (2 types), and recommendations (3 types). Features multi-provider merging, composite type synthesis, genre affinity discovery, similar albums via era-proximity scoring, and pluggable catalog-aware filtering.

## Core Value

Consumers get comprehensive, accurate music metadata from a single `enrich()` call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## Current State

Shipped v0.6.0 Recommendations Engine with 31 enrichment types across 11 providers. Three new recommendation types (ARTIST_RADIO, SIMILAR_ALBUMS, GENRE_DISCOVERY), SIMILAR_ARTISTS upgraded to multi-provider mergeable type (Last.fm + ListenBrainz + Deezer), engine extensibility via ResultMerger and CompositeSynthesizer interfaces, and CatalogProvider interface for availability-aware filtering.

## Requirements

### Validated

- v0.1.0: 11 providers, 16 enrichment types, priority chains, circuit breakers, rate limiting
- v0.1.0: Identity resolution pipeline (MusicBrainz -> MBID/Wikidata/Wikipedia)
- v0.1.0: Fan-out concurrency, confidence filtering, configurable priorities
- v0.1.0: Pure Kotlin/JVM core + optional Android module (Room, Hilt, WorkManager)
- v0.4.0: 5 provider bugs fixed (MusicBrainz, Last.fm, LRCLIB, Wikidata)
- v0.4.0: Typed IdentifierRequirement enum, identity provider formalization, mapper pattern, ApiKeyConfig
- v0.4.0: TTL in EnrichmentType enum, extensible identifiers, ErrorKind, HttpResult
- v0.4.0: 6 new enrichment types (BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, SIMILAR_TRACKS, ARTIST_BANNER, ARTIST_LINKS)
- v0.4.0: Artwork sizes, back cover/booklet art, album metadata deepening
- v0.4.0: Track-level popularity fix, ListenBrainz batch endpoints, ConfidenceCalculator
- v0.5.0: HttpResult migration across all 11 provider APIs, ErrorKind adoption with mapError()
- v0.5.0: ListenBrainz ARTIST_DISCOGRAPHY wired, Discogs release/master ID storage
- v0.5.0: CREDITS type (MusicBrainz recording rels + Discogs extraartists) with roleCategory
- v0.5.0: RELEASE_EDITIONS type (MusicBrainz release-group + Discogs master versions)
- v0.5.0: ARTIST_TIMELINE composite type with automatic sub-type resolution
- v0.5.0: GenreTag confidence scores, GenreMerger, ProviderChain.resolveAll() for mergeable types
- v0.5.0: Provider coverage expansion (Last.fm album info, iTunes lookups, Fanart.tv album art, ListenBrainz similar artists, Discogs community data)
- v0.6.0: ResultMerger and CompositeSynthesizer interfaces extracted from engine
- v0.6.0: Deezer SIMILAR_ARTISTS via /artist/{id}/related, SIMILAR_ARTISTS promoted to mergeable with SimilarArtistMerger
- v0.6.0: ARTIST_RADIO type via Deezer /artist/{id}/radio endpoint
- v0.6.0: SIMILAR_ALBUMS standalone provider with era-proximity scoring
- v0.6.0: GENRE_DISCOVERY composite type with static genre affinity taxonomy (~70 relationships)
- v0.6.0: CatalogProvider interface with UNFILTERED/AVAILABLE_ONLY/AVAILABLE_FIRST modes
- v0.6.0: SimilarArtist.sources field for merge transparency across 3 providers

### Active

(None — planning next milestone)

### Out of Scope

- New providers (Spotify, Apple Music, etc.) — focus on extracting more from existing 11
- Android module changes — core-only focus
- Wikipedia structured HTML parsing — high complexity, low ROI vs Wikidata
- ForAlbum credits aggregation — deferred from v0.5.0
- Genre taxonomy hierarchy — flat affinity table covers the use case
- Credit-based discovery — cross-entity query pattern, deferred to v0.7.0
- ListenBrainz collaborative filtering — user-scoped, needs user identity concept, deferred to v0.8.0
- CatalogProvider implementations (LocalLibrary, Spotify, etc.) — interface only in v0.6.0, implementations v0.8.0

## Context

- Pre-1.0 with no external consumers — clean breaking changes still safe
- Provider APIs are the biggest long-term maintenance risk — mitigated by mapper pattern
- v0.5.0 tech debt: itunesArtistId not stored in resolvedIdentifiers (re-searches on every discography call)
- v0.6.0 tech debt: DefaultEnrichmentEngine.kt is 304 lines (4 over 300-line target)
- 31 enrichment types, 4 engine concepts (provider chains, composite types, mergeable types, catalog filtering)
- Engine extensibility: new mergeable types via ResultMerger, new composites via CompositeSynthesizer

## Constraints

- **Stack**: Pure Kotlin/JVM, Java 17, org.json for parsing, kotlinx.serialization
- **Style**: 200-line files (300 max), 20-line functions (40 max), no `!!`
- **Testing**: Fakes over mocks, runTest for unit tests, runBlocking for E2E
- **API compliance**: MusicBrainz 1 req/sec, descriptive User-Agent for Wikimedia APIs
- **Dependencies**: Managed via gradle/libs.versions.toml version catalog

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| MusicBrainz as identity backbone | MBIDs + Wikidata/Wikipedia links enable precise downstream lookups | ✓ Good |
| Provider mapper pattern | Isolate provider code from public API shape; changes to EnrichmentData only touch mappers | ✓ Good — 11 mappers, zero inline construction |
| Clean breaks over deprecation | No external consumers at pre-1.0; deprecation adds complexity for zero benefit | ✓ Good |
| Typed IdentifierRequirement enum | Boolean `requiresIdentifier` too coarse; providers need MUSICBRAINZ_ID vs WIKIDATA_ID | ✓ Good — 6 enum values, precise chain filtering |
| ConfidenceCalculator utility | Standardize confidence scoring across providers without enforcement | ✓ Good — all 11 providers adopted |
| Engine-level composite types | ARTIST_TIMELINE synthesizes from sub-types; reuses existing data without duplicate API calls | ✓ Good — clean separation |
| Engine-level genre merging | Collect all provider results via resolveAll() instead of short-circuit; GenreMerger normalizes + scores | ✓ Good — extensible pattern |
| Discogs ID propagation via extra map | Store release/master IDs in resolvedIdentifiers.extra for downstream phases | ✓ Good — enables Credits + Editions |
| ResultMerger/CompositeSynthesizer extraction | Engine delegates merge+composite logic to interfaces; new types don't modify engine | ✓ Good — SimilarArtistMerger + GenreAffinityMatcher added without touching engine |
| SimilarAlbumsProvider standalone (not composite) | Composites can't trigger new API calls for other entities; standalone provider makes its own Deezer calls | ✓ Good — clean multi-call pattern |
| CatalogProvider as fun interface | SAM conversion lets consumers pass lambdas; filtering modes as enum | ✓ Good — simple contract |
| Additive scoring for SimilarArtistMerger | Consistent with GenreMerger pattern; cross-provider agreement boosts ranking | ✓ Good |
| Deezer artist ID via extra map | Consistent with discogsReleaseId pattern; check cached first, search fallback with ArtistMatcher | ✓ Good — reused across 3 Deezer features |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd:transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-03-23 after v0.6.0 milestone*
