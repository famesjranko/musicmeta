# Review checklist

For a reviewer reading a diff. **Only rules no mechanism catches** — if `./check` gates it, it is
not here, because a checklist item that cannot fail is noise and goes stale unwatched.
`ARCHITECTURE.md` lists what is gated.

`/review-checklist` walks it against a diff. Nothing gates it — a reviewer who does not open this
file is not stopped by anything, which is the accepted cost of the rules that are here.

**Flag, do not fix**, except where a row says otherwise. These rules survive as prose precisely
because they need judgement, and an agent that repairs them on sight repairs the wrong ones.

The rules themselves live in `CLAUDE.md` ("Rules with no mechanism") and the files each row cites —
a row here carries only what a reviewer looks *for*, so a rule change is never a two-file edit.

## Compatibility

- [ ] An `api/*.api` diff whose break was never **flagged to the user**, or is missing from a
      `### Breaking Changes` heading. `CLAUDE.md`'s compatibility rule holds the terms; `apiCheck`
      gates the dump matching the code — nothing gates whether anyone was told.
- [ ] A serialized payload changed. Round-trip tests encode and decode with the same tree, so they
      cannot see a break for data a consumer already persisted — `ARCHITECTURE.md`'s known gaps has
      what that cost. Ask the user about a cache-clear note.

## CHANGELOG

- [ ] A consumer-visible change with no line, a line for something that is not consumer-visible, or
      an entry for behaviour nobody could legitimately have depended on. `CHANGELOG.md`'s header
      holds all three definitions; the caps are gated, the judgement is not.

## Suspend functions and cancellation

- [ ] A new `catch` in a suspend function that swallows `CancellationException` — most often by
      returning a fallback rather than rethrowing. `docs/pitfalls.md` §2 has the worked example, and
      `ARCHITECTURE.md` why the textual rule for it was deleted rather than fixed.

## Comments

`CLAUDE.md`'s comment rule holds the definitions; what differs per defect is the response:

- [ ] History in a comment, a comment restating its code, an orphaned KDoc. **Fix on sight** —
      mechanical, no judgement.
- [ ] Over-long rationale. **Flag only, and weakly** — the longest comments in this repo are its
      most load-bearing, and `docs/pitfalls.md` §10 is why the obvious flags are false positives.

## Tests

- [ ] `// Given -`/`// When -`/`// Then -` label *form* is gated. What is not: labels in the wrong
      order, more than one `When`, assertions sitting in the `Given`, or a clause that names the
      mechanism under test instead of the behaviour being pinned.
- [ ] A change whose only test lives under `e2e/` — untested for merge purposes (`CLAUDE.md`).
- [ ] A line the test-shape check reported as sitting inside a `"""` raw string. It reports rather
      than skips on purpose (`docs/pitfalls.md` §9) — a report is a line to read, not noise.

## Providers

- [ ] A new provider off the `CLAUDE.md` layout. The visibility half is gated — only `*Provider`
      may be public under `provider/` in `api/*.api` — so what is left for review is the file
      layout and the names themselves.
- [ ] A capability or provider behaviour change with no matching edit to `docs/providers.md`.
      Nothing checks that file; two mechanisms for it were built and both deleted. Its
      hand-verified date is its only warranty, so moving the date is part of the change.
