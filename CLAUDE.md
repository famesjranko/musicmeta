# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Documentation

| File | Purpose |
|------|---------|
| `README.md` | Project overview, setup instructions, API examples |
| `CLAUDE.md` | AI coding instructions (this file) |
| `STORIES.md` | Architectural decisions, progress log, rationale |
| `CHANGELOG.md` | Release history (user-facing, Keep a Changelog format) |
| `ROADMAP.md` | Gap analysis, coverage matrix, planned milestones |

**For AI agents**: Check `STORIES.md` for context on *why* decisions were made. Update it when making significant architectural choices or completing milestones. Update `CHANGELOG.md` when adding features or fixing bugs.

## Build & Test Commands

```bash
# Build everything (core + android)
./gradlew build

# Build core module only (pure JVM, no Android SDK needed)
./gradlew :musicmeta-core:build

# Run core unit tests
./gradlew :musicmeta-core:test

# Run a single test class
./gradlew :musicmeta-core:test --tests "com.landofoz.musicmeta.engine.ProviderChainTest"

# Run a single test method
./gradlew :musicmeta-core:test --tests "com.landofoz.musicmeta.engine.ProviderChainTest.skips unavailable providers"

# Run E2E tests (hit real APIs, skipped by default)
./gradlew :musicmeta-core:test -Dinclude.e2e=true

# Run android module tests (requires Android SDK)
./gradlew :musicmeta-android:test

# Publish to local Maven repo
./gradlew publishToMavenLocal
```

## Architecture

**Two-module Gradle project** published as `com.landofoz:musicmeta-core` and `com.landofoz:musicmeta-android` via JitPack.

### musicmeta-core (pure Kotlin/JVM)

The engine resolves music metadata through a pipeline:

1. **Identity resolution** — `MusicBrainzProvider` searches by title/artist, returns an MBID plus Wikidata/Wikipedia links. This enriches the `EnrichmentRequest` with `EnrichmentIdentifiers` so downstream providers can do precise lookups instead of fuzzy search.

2. **Provider chains** — `ProviderRegistry` builds a `ProviderChain` per `EnrichmentType`, ordered by `ProviderCapability.priority` (100 = primary, 50 = fallback). The chain tries providers in order; `NotFound` falls through, `Success` short-circuits.

3. **Fan-out** — `DefaultEnrichmentEngine.resolveTypes()` runs all requested type chains concurrently via `coroutineScope { async {} }`.

4. **Confidence filtering** — Results below `EnrichmentConfig.minConfidence` (default 0.5) are discarded. Per-provider overrides via `confidenceOverrides` map.

Key types:
- `EnrichmentEngine` — public interface + Builder. Builder wires providers → `ProviderRegistry` → `DefaultEnrichmentEngine`.
- `EnrichmentRequest` — sealed class: `ForAlbum`, `ForArtist`, `ForTrack`. Carries `EnrichmentIdentifiers` that get progressively filled.
- `EnrichmentResult` — sealed class: `Success`, `NotFound`, `RateLimited`, `Error`.
- `EnrichmentProvider` — interface each provider implements. Each provider has its own `Api` class (HTTP calls), `Models` (response parsing), and `Provider` (enrichment logic).

Provider internal structure (each under `provider/<name>/`):
- `*Api.kt` — raw HTTP calls, returns parsed models
- `*Models.kt` — data classes for API responses
- `*Mapper.kt` — transforms API models into `EnrichmentData` types
- `*Provider.kt` — implements `EnrichmentProvider`, orchestrates Api → Mapper → `EnrichmentResult`

Infrastructure in `http/`:
- `HttpClient` interface with `DefaultHttpClient` (java.net.HttpURLConnection)
- `RateLimiter` — per-provider delay between requests
- `CircuitBreaker` — per-provider, shared across chains via `ProviderRegistry`

### musicmeta-android

Adds Android-specific integrations on top of core:
- `RoomEnrichmentCache` — Room-backed persistent cache implementing `EnrichmentCache`
- `HiltEnrichmentModule` — Hilt DI wiring for the cache
- `EnrichmentWorker` — WorkManager base worker for background enrichment

## Testing Patterns

- Fakes over mocks — use fakes from `testutil/`: `FakeProvider`, `FakeHttpClient`, `FakeEnrichmentCache`
- Tests use `kotlinx.coroutines.test.runTest` for coroutine testing
- E2E tests in `e2e/` are gated by `-Dinclude.e2e=true` system property (forwarded in build.gradle.kts)
- Android tests use Robolectric
- Test names use backtick style: `` `provider returns NotFound when album has no art` ``
- Tests follow Given-When-Then structure with comments

## Code Style

- Files: 200 lines target, 300 max
- Functions: 20 lines target, 40 max
- No `!!` — handle nullability properly
- Pure functions when possible
- Explicit over implicit — no magic

## Git Rules

- **DO NOT** add Co-Authored-By, "Generated with Claude", or any AI/Anthropic references to commits or PR messages
- **DO NOT** use `git revert` to undo changes — use `git reset` or manual edits instead
- Always ask permission before running destructive git commands

## Key Conventions

- Package: `com.landofoz.musicmeta` (core), `com.landofoz.musicmeta.android` (android module)
- Group ID: `com.landofoz`
- Java 17 target
- Dependencies managed via `gradle/libs.versions.toml` version catalog
- JSON parsing uses `org.json` (not Gson/Moshi); serialization uses `kotlinx.serialization`
- MusicBrainz requires a descriptive User-Agent string — always set via `DefaultHttpClient` constructor or `EnrichmentConfig.userAgent`
