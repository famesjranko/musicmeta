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
| Changing a public signature, a `catch`, a provider's parsing, or a `ProviderCapability` | `docs/pitfalls.md` |
| Touching `enrich()` or anything it calls | `docs/pitfalls.md` — "Traps in the pipeline" |
| Treating a green run as proof | `ARCHITECTURE.md` — what each check skips |
| Deciding whether a thing is in scope, or what `1.0.0` waits on | `ROADMAP.md` |
| Looking for the issue list | `.scratch/`, **not** GitHub Issues — `docs/agents/issue-tracker.md` |

## Where it goes

Every finding has exactly one home; this file is the home only for the last row.

| A new | Goes in |
|---|---|
| Trap that cost something | `docs/pitfalls.md` |
| Consumer-visible change | a `CHANGELOG.md` line — that file's header defines consumer-visible and the shape |
| Work item, or a finding to triage later | a `.scratch/` ticket — `docs/agents/issue-tracker.md` |
| Rule no mechanism catches | here, and nowhere else |

## Rules with no mechanism

- Compatibility: **flag any break to the user before proceeding.** Published to Maven Central and
  JitPack, so assume external consumers exist. Minor (`0.x.0`) may break, if the break is under a
  `### Breaking Changes` heading in `CHANGELOG.md` *and* visible in the reviewed `api/*.api` diff — a
  break in neither is a defect. Patch (`0.x.y`) may not break. Full semver at `1.0.0`.
  What counts as breaking, and the JVM descriptor caveat: `docs/pitfalls.md`.
- `e2e/` tests hit live APIs behind `-Dinclude.e2e=true` and never gate a merge, so an e2e test is
  not coverage for a change.
- Comments carry the contract, not the history. KDoc states what a caller must know; a rationale
  that isn't a caller's problem gets one sentence, not a paragraph. No PR/issue numbers, `.scratch/`
  paths, or "previously we…" in code — git and the PR hold those. A comment that restates the code
  under it, or that documents nothing (an orphaned KDoc above a blank line), is a defect.
- A new provider is `provider/<name>/` as `*Api`, `*Models`, `*Mapper` (all `internal`) and a public
  `*Provider`. Keeping the first three `internal` is what lets them be renamed without an `apiDump`.
- A test body is `// Given — <what is set up>`, `// When — <the one call>`, `// Then — <what must
  hold>`, each on its own line. Em dash and a clause on every one: a bare `// Given` marks a phase
  without stating it, and is what the reader needed. Multiple acts get one `// When` naming each.
  `// Given / When — <clause>` and other combined-label lines are not allowed, even when the setup
  and the call are one line of code — split into separate `// Given —` / `// When —` lines regardless.
