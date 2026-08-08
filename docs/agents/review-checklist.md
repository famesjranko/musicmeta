# Review checklist

For a reviewer reading a diff. **Only rules no mechanism catches** — if `./check` gates it, it is
not here, because a checklist item that cannot fail is noise and goes stale unwatched.
`ARCHITECTURE.md` lists what is gated.

`/review-checklist` walks it against a diff. Nothing gates it — a reviewer who does not open this
file is not stopped by anything, which is the accepted cost of the rules that are here.

**Flag, do not fix**, except where a row says otherwise. These rules survive as prose precisely
because they need judgement, and an agent that repairs them on sight repairs the wrong ones.

## Compatibility

- [ ] A public signature moved without the break being **flagged to the user**. A minor may break;
      it needs a `### Breaking Changes` heading in `CHANGELOG.md` *and* the change visible in the
      reviewed `api/*.api` diff. A break in neither is a defect. A patch may not break at all.
      `apiCheck` gates the dump matching the code — nothing gates whether anyone was told.
- [ ] A serialized payload changed. Round-trip tests encode and decode with the same tree, so they
      cannot see a break for data a consumer already persisted — `ARCHITECTURE.md`'s known gaps has
      what that cost. Ask the user about a cache-clear note.

## CHANGELOG

- [ ] A consumer-visible change with no line, or a line for something that is not consumer-visible
      (CI, tooling, formatting, repo hygiene). `CHANGELOG.md`'s header defines the term and the
      shape; the caps are gated, the judgement is not.
- [ ] A behaviour change entered for a call that was never advertised. The test is whether a
      consumer could legitimately have depended on it, not whether behaviour moved.

## Suspend functions and cancellation

- [ ] A new `catch` in a suspend function that swallows `CancellationException` — most often by
      returning a fallback rather than rethrowing. `docs/pitfalls.md` §2 has the worked example, and
      `ARCHITECTURE.md` why the textual rule for it was deleted rather than fixed.

## Comments

- [ ] History in a comment: a PR or issue number, a `.scratch/` path, "previously we…". Git and the
      PR hold those. **Fix on sight** — mechanical, no judgement.
- [ ] A comment that restates the code under it, or a KDoc orphaned above a blank line. Both are
      defects. **Fix on sight.**
- [ ] Rationale that is not a caller's problem running past one sentence. **Flag only, and weakly** —
      the longest comments in this repo are its most load-bearing. `docs/pitfalls.md` §10 is why no
      gate exists here and why the obvious flags are false positives.

## Tests

- [ ] `// Given -`/`// When -`/`// Then -` label *form* is gated. What is not: labels in the wrong
      order, more than one `When`, assertions sitting in the `Given`, or a clause that names the
      mechanism under test instead of the behaviour being pinned.
- [ ] A change whose only test lives under `e2e/`. Those hit live APIs behind `-Dinclude.e2e=true`
      and never gate a merge, so that change is untested for merge purposes.
- [ ] A line the test-shape check reported as sitting inside a `"""` raw string. It reports rather
      than skips on purpose (`docs/pitfalls.md` §9) — a report is a line to read, not noise.

## Providers

- [ ] A new provider that is not `provider/<name>/` as `*Api`, `*Models`, `*Mapper` plus a public
      `*Provider`. The visibility half is gated — only `*Provider` may be public under `provider/`
      in `api/*.api` — so what is left for review is the layout and the names themselves.
- [ ] A capability or provider behaviour change with no matching edit to `docs/providers.md`.
      Nothing checks that file; two mechanisms for it were built and both deleted. Its
      hand-verified date is its only warranty, so moving the date is part of the change.
