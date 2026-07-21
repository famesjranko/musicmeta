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
| `docs/agent-workflows/conventions.md` | Issue → PR → dev → main shipping conventions (branch model, verification surface, danger zones) |

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

# Check the public ABI against the committed api/*.api baselines (also runs as part of `build`)
./gradlew apiCheck

# Regenerate the baselines after an intentional API change — commit the resulting .api diff
./gradlew apiDump
```

`demo/` is a **separate composite build** and is never compiled by `./gradlew build`, so it is the
only in-tree consumer that compiles against the published surface the way an external consumer does:

> **The canary is not a parameter-position guard. `apiCheck` is.**
>
> It catches removals, renames and return-type changes — anything that stops a real consumer
> compiling. What it cannot reliably catch is a parameter *inserted mid-list* **with a default**,
> because Kotlin rebinds positional arguments silently: the break then surfaces only if the
> rebinding also produces a **type** error. (An inserted parameter with *no* default fails outright
> with "no value passed for parameter" — but that form is already forbidden outright below.) v0.9.2 was caught by luck of typing — `Set<EnrichmentType>` could not bind to
> `EnrichmentIdentifiers?`. Insert a `String?` between `artist` and `mbid` on `albumProfile` and the
> demo's third positional argument binds to the new parameter, `mbid` quietly takes its default, and
> everything compiles — wrong, and green.
>
> So: run `apiCheck` and read the `.api` diff for anything touching a signature. Treat a green canary
> as evidence that consumers still *compile*, never as evidence that argument order is unchanged.

```bash
cd demo && ../gradlew compileKotlin
```

## Architecture

**Three-module Gradle project** published as `io.github.famesjranko:musicmeta-core`, `io.github.famesjranko:musicmeta-android`, and `io.github.famesjranko:musicmeta-okhttp` via Maven Central.

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
- Tests follow Given-When-Then structure with comments - using proper: what is given, what action is taken, and what outcome is expected — not just bare section markers.

## Code Style

- Files: 200 lines target, 300 max
- Functions: 20 lines target, 40 max
- No `!!` — handle nullability properly
- Pure functions when possible
- Explicit over implicit — no magic
- **Comments:** Explain only non-obvious constraints, traps, guards, or safety rationale — plus the Given-When-Then narration tests require (see Testing Patterns). Do not restate code or preserve history; put that in issues, PRs, or docs. Remove comments when the related code is removed.

## Modeling Rules

- Enums for fixed sets — never string constants
- Sealed classes for variants with different data — each variant is a data class
- Interfaces for contracts and strategies (providers, cache, mergers)
- Data classes over Pair/Triple/Map for structured fields
- Collection wrappers only when they carry semantic meaning (sealed class variants) — not for API return types
- @Serializable only on public API payload types (EnrichmentData subtypes, EnrichmentIdentifiers) — never on provider models or infrastructure

## Backwards Compatibility

This library is published on Maven Central and JitPack — assume external consumers exist.

**Versioning stance — the 0.x carve-out.** This project is pre-1.0, and during the `0.x` series semver permits breaking changes on minor releases. This repo adopts that carve-out explicitly, because the earlier "no breaks without a major bump" rule was never actually true of practice and that gap is what let the audit findings accumulate (see `STORIES.md`, 2026-07-21):

- **Minor releases (`0.x.0`) MAY contain breaking public-API changes** — but every break MUST be documented under a `### Breaking Changes` heading in `CHANGELOG.md` and be visible in the reviewed `api/*.api` diff. A break that is neither documented nor visible in the `.api` diff is a defect, not a release.
- **Patch releases (`0.x.y`, `y > 0`) may NOT break the public API.** Patches are for fixes that keep the signature and ABI stable. (v0.9.2 broke this by inserting a parameter mid-list in a patch — the specific mistake this rule exists to stop recurring.)
- **At `1.0.0` the full semver rule takes effect:** no breaking change to the public API without a major version bump, from then on.

Whatever the release channel, evolve the API additively wherever possible:

- Breaking = removing/renaming public classes, functions, or parameters; changing return types; reordering non-named parameters; changing enum/sealed-class variants consumers may match on.
- Prefer adding new overloads or default parameters over modifying existing signatures.
- Deprecate before removing — add `@Deprecated` with `ReplaceWith` and keep for at least one minor release before removal.
- **Append new parameters at the end with a default — never insert mid-list.** A mid-list insertion silently re-binds every positional argument after it. (Note: on the JVM, adding a parameter *anywhere* to a function with default parameters changes the generated method descriptor, so it is a binary break for pre-compiled callers regardless of position — appending is the source-compatible floor, not a full ABI guarantee. A 0.x minor is the place for either.)
- Internal code (`internal` visibility, `provider/*/` internals, `http/` infrastructure) can change freely — and this is now enforced by visibility, not just aspiration: the `*Api`/`*Mapper`/`*Models` behind each provider, `MusicBrainzParser`, `CircuitBreaker`, and the `engine/` mergers/synthesizers are all `internal` and absent from the `.api` baselines. The public surface is the `*Provider` classes, `HttpClient`/`HttpResult`/`HttpResponse`/`DefaultHttpClient`/`RateLimiter`, and the `ResultMerger`/`CompositeSynthesizer` extension-point interfaces.
- When you make a breaking change, document it in `CHANGELOG.md` under a `### Breaking Changes` heading and flag it to the user before proceeding.

**Enforcement.** `binary-compatibility-validator` dumps the public ABI to `api/*.api` in each module.
`apiCheck` runs as part of `./gradlew build` and in the publish workflow, so a diverging signature
fails the build rather than being caught by review alone. An intentional API change means running
`./gradlew apiDump` and reviewing the committed `.api` diff — that diff, not the source diff, is the
record of what consumers see.

> **The provider/http/engine surface was narrowed (2026-07-21, issue #5).** 80 top-level types that
> were public only by omission — the `*Api`/`*Mapper`/`*Models` behind each provider, `MusicBrainzParser`,
> `http/CircuitBreaker`, and the `engine/` mergers/synthesizers — are now `internal` and no longer in the
> `.api` baselines. A refactor confined to those internals no longer touches the public ABI, so `apiCheck`
> stays green and no `apiDump` commit is needed. `RateLimiter` stays public deliberately: it is a
> parameter of nearly every public `*Provider` constructor, so hiding it would break the provider
> constructor surface far more than it narrows anything. What remains public under `engine/`
> (`ProviderRegistry`, `ProviderChain`, `DefaultEnrichmentEngine`, `ArtistMatcher`, `ConfidenceCalculator`)
> is engine wiring/helpers left for a future pass. One exception was forced here: `ProviderChain`'s
> constructor became `internal` (its default `circuitBreakers` parameter referenced the now-internal
> `CircuitBreaker`); the class itself stays public and is reachable via `ProviderRegistry.chainFor()`.

## Git Rules

- **DO NOT** add Co-Authored-By, "Generated with Claude", or any AI/Anthropic/tool attribution in commits, PR titles and bodies, issue comments, or any other text that leaves this machine
- **DO NOT** use `git revert` to undo changes — use `git reset` or manual edits instead
- Always ask permission before running destructive git commands

## Key Conventions

- Package: `com.landofoz.musicmeta` (core), `com.landofoz.musicmeta.android` (android module)
- Group ID: `io.github.famesjranko`
- Java 17 target
- Dependencies managed via `gradle/libs.versions.toml` version catalog
- JSON parsing uses `org.json` (not Gson/Moshi); serialization uses `kotlinx.serialization`
- MusicBrainz requires a descriptive User-Agent string — always set via `DefaultHttpClient` constructor or `EnrichmentConfig.userAgent`
