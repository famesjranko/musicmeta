# CLAUDE.md

Holds only the rules no mechanism catches, so silence here is not permission. Where this file and a
config disagree, follow the config — the config is the thing that fails. Don't write history into
docs; git and the PR hold what happened.

Run `make check` before claiming anything works; CI runs the same script. `make check-fast` is edit loop
only, **never evidence for a push**; `check`'s header lists what it skips. `make help` lists the
rest; `ls docs/` lists the docs.

## Read first

| Before | Read |
|---|---|
| Touching `enrich()` or anything it calls | `docs/pitfalls.md` — "Traps in the pipeline" |
| Changing a public signature, an `api/*.api` file, or a public data class | `docs/pitfalls.md` — "The published surface" |
| Naming a public type or member, or reading a provider's mapper | `docs/glossary.md` — one word per concept, and each upstream's word for it |
| Writing a `catch`, a timeout, breaker or fallback behaviour, or classifying a result as `Error`/`NotFound` | `docs/pitfalls.md` — "Errors, cancellation, and timeouts" |
| A provider's parsing, search/ranking, `confidence`, or a `ProviderCapability` | `docs/pitfalls.md` — "Provider data and matching" |
| Retry or status mapping, state held by a provider, or `forceRefresh`/invalidation | `docs/pitfalls.md` — "Transport and provider state" |
| `scripts/checks/`, the comment rule, or demo config | `docs/pitfalls.md` — "Checks, comments, and config" |
| Treating a green run as proof | `VERIFICATION.md` — what each check skips |
| Designing across module boundaries, or adding a provider | `ARCHITECTURE.md` — the seams and what they cost |
| Deciding whether a thing is in scope, or what `1.0.0` waits on | `ROADMAP.md` |
| Looking for the issue list | `.scratch/`, **not** GitHub Issues — a dependency bump is the one exception; `docs/agents/issue-tracker.md` |
| Reviewing a diff, a branch, or a PR | `docs/agents/review-checklist.md` — the unmechanised rules only |

## Where it goes

Every finding has exactly one home, and lands in the same commit as the change that taught it —
nothing can catch a trap nobody wrote down. This file is the home only for the last row.

**Two audiences, and the boundary is `README.md`'s documentation table.** Everything in it ships to
someone who took the library and will never see this repo: `README.md`, `docs/guides/`,
`docs/how-it-works.md`, `docs/glossary.md`, `docs/providers.md`, `docs/project/`, `CHANGELOG.md`,
`ARCHITECTURE.md`, `VERIFICATION.md`. Everything outside it is ours: this file, `docs/pitfalls.md`,
`docs/agents/`, `.scratch/`. Write for the reader the file has — a consumer reading `ARCHITECTURE.md`
wants the invariant and what it costs them, not which check we wrote, kept or deleted. The two
shipped files that are *about* the repo, `ARCHITECTURE.md` and `VERIFICATION.md`, are where this
goes wrong: the split between them is the system versus what verifies it.

| A new | Goes in |
|---|---|
| Trap that cost something | `docs/pitfalls.md` |
| Consumer-visible change | a `CHANGELOG.md` line — that file's header defines consumer-visible and the shape |
| Work item, or a finding to triage later | a `.scratch/` ticket — `docs/agents/issue-tracker.md`. A dependency bump goes to GitHub Issues instead |
| Rule no mechanism catches | here, and nowhere else — `docs/agents/review-checklist.md` may add how review *applies* a rule, never the rule itself |
| Fact about our own tooling — a gate that does not exist, a mechanism tried and dropped | `VERIFICATION.md` — "Known gaps". Never a design doc: `ARCHITECTURE.md` says what the system is, not what we check |

## Rules with no mechanism

Where a rule below names its gate, what is written here is the part that gate cannot see.

- Compatibility: **flag any break to the user before proceeding.** Published to Maven Central and
  JitPack, so assume external consumers exist. Minor (`0.x.0`) may break, if the break is under a
  `### Breaking Changes` heading in `CHANGELOG.md` *and* visible in the reviewed `api/*.api` diff — a
  break in neither is a defect. Patch (`0.x.y`) may not break. Full semver at `1.0.0`.
  What counts as breaking, and the JVM descriptor caveat: `docs/pitfalls.md` — "The published surface".
- A test is written before the code it pins and watched fail for the stated reason. A test first
  seen green proves that its assertions run, not that they could catch anything. Nothing mechanises
  this, so the claim carries the evidence: name the mutation and the test that went red. Never
  weaken, skip or `@Ignore` a test to reach green — if a test is wrong, say why before changing it.
- A dependency in `musicmeta-core` reaches every consumer transitively and cannot be withdrawn
  without a break, so each one carries its argument beside the declaration and a fourth needs one
  too. The adapters exist to bring OkHttp and Room and are not held to this. Nothing enforces it
  (`VERIFICATION.md` — "Known gaps").
- `e2e/` tests hit live APIs behind `-Dinclude.e2e=true` and never gate a merge, so an e2e test is
  not coverage for a change. `musicmeta-android/src/androidTest/` is the same: nothing runs it —
  not `check`, not CI — so a Room migration reaches a release having been proved only against
  Robolectric's SQLite. Changing `EnrichmentCacheDatabase`'s schema means writing the device test
  *and* saying that it was run on a device, at the commit, not at tagging. It is the one change
  this repo makes that a revert cannot undo: the schema is already on the user's phone.
- A `@Serializable` cache type is a compatibility surface that no gate reads. It moves no
  `api/*.api` line, and the round-trip tests encode and decode with the same tree, so they cannot
  see a payload a consumer already persisted becoming unreadable — that is v0.4.0, which broke
  every Room cache entry (`VERIFICATION.md` — "Known gaps"). Treat a change to one as a break under
  the rule above, and ask the user about a cache-clear note.
- A provider test asserts against a fixture copied from a real upstream response, and a fixture
  must predate the change it is evidence for — one written to match new code proves only that the
  code agrees with itself. A pool whose chain back to a live capture is unverified says so in its
  own `scenario.md`.
- A design choice between plausible options is settled by measurement when a cheap probe exists:
  one throwaway arm per option, identical workload and metrics, decided from the arms' own numbers.
  Each arm's report states what it did not measure, lands in `.scratch/<effort>/prototypes/`, and
  its worktree is committed to a branch — an uncommitted worktree is the only copy of its evidence.
  A prose comparison is the fallback for when no cheap probe exists, and says so.
- Comments carry the contract, not the history. KDoc states what a caller must know; a rationale
  that isn't a caller's problem gets one sentence, not a paragraph. No PR/issue numbers, `.scratch/`
  paths, or "previously we…" in code — git and the PR hold those. A comment that restates the code
  under it, or that documents nothing (an orphaned KDoc above a blank line), is a defect.
- A new provider is `provider/<name>/` as `*Api`, `*Models`, `*Mapper` (all `internal`) and a public
  `*Provider`. Keeping the first three `internal` is what lets them be renamed without an `apiDump`.
- A Kotlin test body needs `// Given -`/`// When -`/`// Then -`, each on its own line with a plain
  hyphen and a real clause — one label per line, never combined. A hyphen, not an em dash, so the
  form is typeable without a compose key. Shape and exact form:
  `scripts/checks/check_test_shape.py`, which mechanises this and gates it in `format-on-write.sh`
  and `check`.
