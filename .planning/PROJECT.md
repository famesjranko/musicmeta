# musicmeta

## What This Is

A pure Kotlin/JVM music metadata enrichment library that aggregates data from 11 public APIs (MusicBrainz, Last.fm, Wikidata, Wikipedia, Cover Art Archive, Fanart.tv, Deezer, iTunes, Discogs, ListenBrainz, LRCLIB) into a unified pipeline with priority chains, circuit breakers, rate limiting, and identity resolution. 28 enrichment types across 7 categories: artwork (7 types with multi-size support), metadata (9 types), text (3 types), relationships (3 types), statistics (2 types), links (2 types), and composite (2 types). Features composite type resolution, mergeable multi-provider genre merging, and per-tag confidence scoring.

## Core Value

Consumers get comprehensive, accurate music metadata from a single `enrich()` call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## Current State

Shipped v0.5.0 with 28 enrichment types across 11 providers. All providers use HttpResult/ErrorKind uniformly. Three new enrichment types (CREDITS, RELEASE_EDITIONS, ARTIST_TIMELINE) plus genre enhancement with multi-provider merging. Provider coverage expanded with new Last.fm, iTunes, Fanart.tv, and ListenBrainz endpoints.

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

### Active

(None — planning next milestone)

### Out of Scope

- New providers (Spotify, Apple Music, etc.) — focus on extracting more from existing 11
- Android module changes — core-only focus
- Wikipedia structured HTML parsing — high complexity, low ROI vs Wikidata
- ForAlbum credits aggregation — deferred from v0.5.0
- Generic CompositeType registry — ARTIST_TIMELINE handled specifically for now
- Genre taxonomy hierarchy — deferred

## Context

- Pre-1.0 with no external consumers — clean breaking changes still safe
- Provider APIs are the biggest long-term maintenance risk — mitigated by mapper pattern
- v0.5.0 tech debt: itunesArtistId not stored in resolvedIdentifiers (re-searches on every discography call)
- 28 enrichment types, 3 engine concepts (provider chains, composite types, mergeable types)

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
*Last updated: 2026-03-22 after v0.5.0 milestone*
