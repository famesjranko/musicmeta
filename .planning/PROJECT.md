# musicmeta

## What This Is

A pure Kotlin/JVM music metadata enrichment library that aggregates data from 11 public APIs (MusicBrainz, Last.fm, Wikidata, Wikipedia, Cover Art Archive, Fanart.tv, Deezer, iTunes, Discogs, ListenBrainz, LRCLIB) into a unified pipeline with priority chains, circuit breakers, rate limiting, and identity resolution. Consumers get artwork, metadata, lyrics, bios, and more for any artist/album/track with a single call.

## Core Value

Consumers get comprehensive, accurate music metadata from a single `enrich()` call without knowing which APIs exist, how they authenticate, or how to correlate identifiers across services.

## Current Milestone: v0.4.0 Provider Abstraction Overhaul

**Goal:** Isolate provider internals from the public API, fix known bugs, clean up the public type surface, add 6 new enrichment types, and deepen existing type coverage across all providers.

**Target features:**
- Fix 5 known provider bugs (MusicBrainz, Last.fm, LRCLIB, Wikidata)
- Provider abstraction layer (typed identifiers, identity provider formalization, mapper pattern, API key management)
- Public API cleanup (TTL in EnrichmentType, extensible identifiers, error categorization, HttpResult)
- 6 new enrichment types (BAND_MEMBERS, ARTIST_DISCOGRAPHY, ALBUM_TRACKS, SIMILAR_TRACKS, ARTIST_BANNER, ARTIST_LINKS)
- Artwork sizes enhancement
- Deepen existing types (back cover, booklet, track-level popularity, batch endpoints, confidence standardization)

## Requirements

### Validated

- v0.1.0: 11 providers, 16 enrichment types, priority chains, circuit breakers, rate limiting
- v0.1.0: Identity resolution pipeline (MusicBrainz -> MBID/Wikidata/Wikipedia)
- v0.1.0: Fan-out concurrency, confidence filtering, configurable priorities
- v0.1.0: Pure Kotlin/JVM core + optional Android module (Room, Hilt, WorkManager)

### Active

- [ ] Fix known bugs in MusicBrainz, Last.fm, LRCLIB, Wikidata providers
- [ ] Typed identifier requirements replacing boolean `requiresIdentifier`
- [ ] Formalize identity resolution as a provider role (not engine heuristic)
- [ ] Provider mapper pattern (separate DTO-to-EnrichmentData mapping)
- [ ] Centralized API key management
- [ ] TTL moved into EnrichmentType enum
- [ ] Extensible EnrichmentIdentifiers with extra map
- [ ] Error categorization (ErrorKind enum)
- [ ] HttpResult sealed class for typed HTTP responses
- [ ] 6 new enrichment types with provider implementations
- [ ] Artwork sizes (multiple sizes per result)
- [ ] Back cover and booklet art types
- [ ] Track-level popularity fix (Last.fm track.getInfo, ListenBrainz batch)
- [ ] Confidence scoring standardization
- [ ] ListenBrainz batch endpoints

### Out of Scope

- New providers (Spotify, Apple Music, etc.) — focus on extracting more from existing 11
- Android module changes — core-only for this milestone
- CREDITS type — high effort, deferred to future milestone (PRD Phase 3A)
- RELEASE_EDITIONS type — medium effort, deferred (PRD Phase 3B)
- Artist timeline — depends on Phase 3 types existing (PRD Phase 3C)
- Genre deep dive — deferred (PRD Phase 3D)

## Context

- Pre-1.0 with no external consumers — clean breaking changes are safe
- Provider APIs are the biggest long-term maintenance risk
- Most data needed is already accessible from integrated providers, just not extracted yet
- PRD (docs/PRD.md) provides detailed implementation specs for all changes
- ROADMAP.md provides gap analysis and priority scorecard
- Provider API reference docs in docs/providers/

## Constraints

- **Stack**: Pure Kotlin/JVM, Java 17, org.json for parsing, kotlinx.serialization
- **Style**: 200-line files (300 max), 20-line functions (40 max), no `!!`
- **Testing**: Fakes over mocks, runTest for unit tests, runBlocking for E2E
- **API compliance**: MusicBrainz 1 req/sec, descriptive User-Agent for Wikimedia APIs
- **Dependencies**: Managed via gradle/libs.versions.toml version catalog

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| MusicBrainz as identity backbone | MBIDs + Wikidata/Wikipedia links enable precise downstream lookups | v Good |
| Provider mapper pattern (new) | Isolate provider code from public API shape; changes to EnrichmentData only touch mappers | -- Pending |
| Clean breaks over deprecation | No external consumers at v0.1.0; deprecation adds complexity for zero benefit | -- Pending |
| Remove IdentifierResolution from public API | Internal concept leaked into sealed class; identity resolution is engine-internal | -- Pending |

---
*Last updated: 2026-03-21 after milestone v0.4.0 started*
