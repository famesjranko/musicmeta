# musicmeta

## What This Is

A pure Kotlin/JVM music metadata enrichment library that aggregates data from 11 public APIs (MusicBrainz, Last.fm, Wikidata, Wikipedia, Cover Art Archive, Fanart.tv, Deezer, iTunes, Discogs, ListenBrainz, LRCLIB) into a unified pipeline with priority chains, circuit breakers, rate limiting, and identity resolution. 25 enrichment types across 6 categories: artwork (7 types with multi-size support), metadata (8 types), text (3 types), relationships (3 types), statistics (2 types), and links (2 types).

## Core Value

Consumers get comprehensive, accurate music metadata from a single `enrich()` call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## Current Milestone: v0.5.0 New Capabilities & Tech Debt Cleanup

**Goal:** Complete the "app-ready" story with credits, release editions, artist timeline, genre enhancement, deeper provider coverage, and v0.4.0 tech debt cleanup.

**Target features:**
- Tech debt cleanup (HttpResult migration, ErrorKind adoption, ListenBrainz wiring, Discogs IDs)
- Credits & Personnel (CREDITS type from MusicBrainz + Discogs)
- Release Editions (RELEASE_EDITIONS type from MusicBrainz + Discogs)
- Artist Timeline (ARTIST_TIMELINE composite type synthesizing existing data)
- Genre Enhancement (multi-provider genre merging with GenreTag confidence)
- Provider Coverage Expansion (Last.fm, iTunes, Fanart.tv, ListenBrainz, Discogs deepening)

## Current State

Shipped v0.4.0 with 12,261 LOC Kotlin. 25 enrichment types across 11 providers. All providers use typed identifier requirements, formalized identity resolution, mapper pattern, and standardized confidence scoring.

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

### Active

- [ ] HttpResult migration across all 11 provider APIs
- [ ] ErrorKind adoption across all 11 providers
- [ ] ListenBrainz ARTIST_DISCOGRAPHY capability wiring
- [ ] Discogs release/master ID storage
- [ ] CREDITS enrichment type (MusicBrainz + Discogs)
- [ ] RELEASE_EDITIONS enrichment type (MusicBrainz + Discogs)
- [ ] ARTIST_TIMELINE composite enrichment type
- [ ] Genre enhancement with multi-provider merging and GenreTag
- [ ] Provider coverage expansion (Last.fm, iTunes, Fanart.tv, ListenBrainz endpoints)

### Out of Scope

- New providers (Spotify, Apple Music, etc.) — focus on extracting more from existing 11
- Android module changes — core-only focus
- Wikipedia structured HTML parsing — high complexity, low ROI vs Wikidata

## Context

- Pre-1.0 with no external consumers — clean breaking changes still safe
- Provider APIs are the biggest long-term maintenance risk — now mitigated by mapper pattern
- v0.4.0 tech debt: ErrorKind/HttpResult exist but not yet adopted by providers; ListenBrainz ARTIST_DISCOGRAPHY plumbing exists but not wired as capability
- 328 tests passing (unit + serialization)
- PRD v0.5.0 defines 6 phases: tech debt → credits → editions → timeline → genre → provider expansion

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
| Clean breaks over deprecation | No external consumers at v0.1.0; deprecation adds complexity for zero benefit | ✓ Good — IdentifierResolution cleanly removed |
| Remove IdentifierResolution from public API | Internal concept leaked into sealed class; identity resolution is engine-internal | ✓ Good — replaced by resolvedIdentifiers on Success |
| Typed IdentifierRequirement enum | Boolean `requiresIdentifier` too coarse; providers need MUSICBRAINZ_ID vs WIKIDATA_ID | ✓ Good — 6 enum values, precise chain filtering |
| ConfidenceCalculator utility | Standardize confidence scoring across providers without enforcement | ✓ Good — all 11 providers adopted, zero hardcoded floats |

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
*Last updated: 2026-03-22 after v0.5.0 milestone started*
