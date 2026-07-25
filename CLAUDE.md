# CLAUDE.md

Pointers, not a manual. **Where this file and a config disagree, the config wins** — the config is the
thing that fails. Git and the PR hold what happened; there is no history document.

`make check` is the gate and CI runs the same script. `make check-fast` is the edit loop: it skips
detekt, the build and the demo canary, so **it is never evidence for a push**. `make help` lists the rest.

## Canonical docs

| Need | Read |
|---|---|
| **The five traps that each cost a release** — read before changing a public signature, a `catch`, a provider's parsing, or a `ProviderCapability` | `docs/pitfalls.md` |
| What `make check` enforces, and where green means less than it looks | `ARCHITECTURE.md` |
| Pipeline behaviour end to end, consumer-facing | `docs/how-it-works.md` |
| Consumer how-to: identity, results/errors, cache, config, extension points, Android | `docs/guides/` |
| One provider's endpoints and response fields | `docs/providers/<name>.md` |
| Whether a thing is in scope at all, and what `1.0.0` is waiting on | `ROADMAP.md` |
| Branch topology, issue lifecycle, worktrees, verification selection | `docs/project/workflow.md` |
| Release preparation, tagging, publication | `docs/project/release.md` |
| Where issues live (`.scratch/`, not GitHub Issues) | `docs/agents/issue-tracker.md` |
| Triage role strings · domain doc layout | `docs/agents/triage-labels.md` · `domain.md` |
| Public overview, install, provider table | `README.md` |
| One line per change, headline plus `(#issue)` | `CHANGELOG.md` |

## Architecture map

`enrich()` in `engine/DefaultEnrichmentEngine.kt` is the map — it walks these in order. Paths are
relative to `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/`; bold marks what surprises people.

1. **Cache** — keys from `EntityKey.kt`, reads and writes guarded by `CacheGuard.kt` so a throwing
   cache degrades to a miss. **`invalidate()` and the `manuallySelected` pair are unguarded.**
2. **Identity** — `IdentityHelper.kt`; `ProviderRegistry.identityProvider()` is the single
   `isIdentityProvider = true` provider (MusicBrainz), whose `resolvedIdentifiers` let the rest do MBID
   lookups instead of fuzzy search. **A `NotFound` with `suggestions` short-circuits the fan-out.**
3. **Chains** — one per type from `ProviderRegistry.kt`, ordered by `priority` (100 primary, 50
   fallback) and `priorityOverrides`. **One `http/CircuitBreaker.kt` per provider id, shared across
   every chain.**
4. **Fan-out** — `resolveTypes()` splits three ways: regular → `ProviderChain.resolve()` (first
   `Success` wins); mergeable → `resolveAll()` folded by a `ResultMerger`; composite → a
   `CompositeSynthesizer` after its **`dependencies`, resolved even when the caller did not ask**.
   Both strategy kinds run in `StrategyGuard.kt`, which contains a throw as `Error` for that type only.
5. **Eligibility** — `ProviderChain` skips unavailable providers, unmet `IdentifierRequirement`s and
   open breakers; `filterByConfidence()` demotes a `Success` below `minConfidence` (0.5) to `NotFound`.
6. **Write-back** — `CatalogFilter.kt`, identity stamping, `STALE_IF_ERROR` fallback, then caching
   under the primary key plus a name-alias key when identity added an MBID. **Only the fan-out is
   inside `withTimeout(enrichTimeoutMs)`**, so an expiry does not discard results already fetched.

Root package holds the public surface: `EnrichmentEngine.kt` (interface + `Builder`),
`EnrichmentRequest.kt`, `EnrichmentResult.kt`, `EnrichmentResults.kt`, `EnrichmentData.kt`
(`@Serializable` payloads), `EnrichmentProvider.kt`, `EnrichmentType.kt`, `EnrichmentConfig.kt`,
`EnrichmentCache.kt`.

## Modules and provider layout

`musicmeta-core` is pure Kotlin/JVM, no Android SDK. `musicmeta-android` adds `RoomEnrichmentCache`,
`HiltEnrichmentModule` and `EnrichmentWorker` under Robolectric. `musicmeta-okhttp` is one class.
**`demo/` is a separate composite build** — never compiled by `./gradlew build`, exempt from house
Kotlin style on purpose, and the only in-tree consumer compiling against the published surface.

Each provider is `provider/<name>/`, split four ways. **The split is load-bearing**: the first three
are `internal`, so they can be renamed freely without an `apiDump`.

| File | Holds | Visibility |
|---|---|---|
| `*Api.kt` | HTTP calls, URL building, `org.json` parsing into models | `internal` |
| `*Models.kt` | plain data classes mirroring the API response, nothing else | `internal` |
| `*Mapper.kt` | model → `EnrichmentData` translation, pure functions | `internal` |
| `*Provider.kt` | `EnrichmentProvider`: capabilities, Api → Mapper → `EnrichmentResult` | **public** |

## Rules with no mechanism

- **Compatibility** — published to Maven Central and JitPack, so assume external consumers exist.
  Minor (`0.x.0`) MAY break, with the break under a `### Breaking Changes` heading in `CHANGELOG.md`
  *and* visible in the reviewed `api/*.api` diff; a break in neither is a defect. Patch (`0.x.y`) may
  NOT break — v0.9.2 did. Full semver at `1.0.0`. **Flag any break to the user before proceeding.**
  What counts as breaking, and the JVM descriptor caveat: `docs/pitfalls.md` §1.
- **Tests** mirror the main tree under `src/test/kotlin/...`; `e2e/` hits live APIs, gated by
  `-Dinclude.e2e=true` and never merge-gating. Fakes over mocks — `testutil/FakeProvider`,
  `FakeHttpClient`, `FakeEnrichmentCache`. `runTest` for coroutines. Names are backticked sentences,
  `` `provider returns NotFound when album has no art` ``, with Given-When-Then comments saying what
  is given, done and expected — not bare section markers.
- **MusicBrainz** wants a descriptive User-Agent and max 1 request/second — `RateLimiter(1100)` in
  `EnrichmentEngine.kt`, `EnrichmentConfig.userAgent`. Their terms of service, not our preference.
- **Comments** explain non-obvious constraints, traps and guards — not what the code says — and stay
  to a line or two.

## Doc update triggers

- `ARCHITECTURE.md` — a new mechanism, or a gap found in an existing one.
- `docs/pitfalls.md` — a new trap that cost something.
- This file — a rule that turned out unenforceable, or a doc that moved.
- `CHANGELOG.md` — every change; a payload change asks about a cache-clear note.
