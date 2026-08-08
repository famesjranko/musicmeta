---
description: Walk the unmechanised house rules against a diff
---

Read `docs/agents/review-checklist.md` and walk every item against the changes in scope
(`$ARGUMENTS` if given, otherwise the diff against the merge-base with `main`).

Report one line per item: pass, or the file and line that fails it. Do not repair anything the
checklist marks flag-only — those rules survive as prose because they need judgement, and the
obvious-looking violations are usually the false positives.

`./check` is the authority on everything mechanised; this covers only what it cannot.
