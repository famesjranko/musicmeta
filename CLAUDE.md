# CLAUDE.md

Holds only the rules no mechanism catches, so silence here is not permission. Where this file and a
config disagree, follow the config — the config is the thing that fails. Don't write history into
docs; git and the PR hold what happened.

Run `make check` before claiming anything works; CI runs the same script. `make check-fast` skips
detekt, the build and the demo canary — edit loop only, **never evidence for a push**. `make help`
lists the rest; `ls docs/` lists the docs.

## Read first

| Before | Read |
|---|---|
| Changing a public signature, a `catch`, a provider's parsing, or a `ProviderCapability` | `docs/pitfalls.md` |
| Treating a green run as proof | `ARCHITECTURE.md` — what each check skips |
| Deciding whether a thing is in scope, or what `1.0.0` waits on | `ROADMAP.md` |
| Looking for the issue list | `.scratch/`, **not** GitHub Issues — `docs/agents/issue-tracker.md` |

## Traps in the pipeline

Read `enrich()` in `engine/DefaultEnrichmentEngine.kt` — it is the map, and this list is not
exhaustive. Paths are relative to `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/`.

- `CacheGuard.kt` degrades a throwing cache to a miss, but public `invalidate()`,
  `is`/`markManuallySelected()` and `getIncludingExpired()` are unguarded.
- An identity `NotFound` carrying `suggestions` short-circuits the whole provider fan-out.
- One `http/CircuitBreaker.kt` per provider id, shared across every chain.
- A `CompositeSynthesizer`'s `dependencies` are resolved even when the caller did not ask for them.
- `withTimeoutOrNull(enrichTimeoutMs)` returns null for *that* deadline only; a nested one
  propagates. An expiry does not discard results already fetched, but the write-back is skipped, so
  a timed-out run caches nothing. The stale fallback and write-back sit outside the timed block.
- `filterByConfidence()` demotes a `Success` below `minConfidence` (0.5) to `NotFound`.

## Rules with no mechanism

- Compatibility: **flag any break to the user before proceeding.** Published to Maven Central and
  JitPack, so assume external consumers exist. Minor (`0.x.0`) may break, if the break is under a
  `### Breaking Changes` heading in `CHANGELOG.md` *and* visible in the reviewed `api/*.api` diff — a
  break in neither is a defect. Patch (`0.x.y`) may not break; v0.9.2 did. Full semver at `1.0.0`.
  What counts as breaking, and the JVM descriptor caveat: `docs/pitfalls.md` §1.
- `e2e/` tests hit live APIs behind `-Dinclude.e2e=true` and never gate a merge, so an e2e test is
  not coverage for a change.
- A new provider is `provider/<name>/` as `*Api`, `*Models`, `*Mapper` (all `internal`) and a public
  `*Provider`. Keeping the first three `internal` is what lets them be renamed without an `apiDump`.
- Add a `CHANGELOG.md` line for every consumer-visible change — that file's header defines
  consumer-visible and the shape. A new trap that cost something goes in `docs/pitfalls.md`.
