<!-- Thanks for contributing to musicmeta.

     Not every section applies to every change — delete the ones that don't. The point is to
     preempt a reviewer's questions, not to fill in a form. This body becomes the squash commit,
     so it is also the answer someone gets months from now when `git blame` sends them here.

     CONTRIBUTING.md has the longer version of all of this. -->

## Related links

Closes #

<!-- Anything a reviewer will open more than once: the issue, a prior PR this follows, the
     provider's own API docs, the migration-guide section, the .scratch spec if you have one. -->

## What

<!-- The changes at a high level — a few words each, so a reviewer can tell whether they are the
     right person and how long it will take. -->

-
-

## Why

<!-- Why these changes are necessary. One or two points is plenty. This is the section that pays
     off longest: it is what a `git blame` lands on in six months, when the code looks peculiar and
     nobody remembers the constraint that shaped it. -->

## How

<!-- Only where it is not obvious from the diff. What you tried that did not work and why, what
     you measured, which upstream behaviour forced the shape. Two specific things worth naming:

     - An approach ruled out by measurement — say what the arms were and what the numbers said.
       A prose comparison is the fallback where no cheap probe existed; say that too.
     - An upstream quirk you had to work around. It will look arbitrary to the next reader. -->

## Evidence

<!-- A test first seen green proves that its assertions run, not that they could catch anything.
     So name the mutation and the test that went red: paste the failure, or say "reverted X to
     <sha>^ and these N tests failed for this reason". Nothing mechanises this — it is the claim
     that carries its own proof. -->

- [ ] `make check` is green, all steps — or I have said below which I could not run, and why
- [ ] New or changed behaviour has a test that was **watched fail first**, shown above
- [ ] Any provider fixture I assert against was captured from a real upstream response **before**
      this change, not written to match it

## Published surface

<!-- Delete if this is internal, a doc, or a script. -->

- [ ] `api/*.api` is unchanged, or `make api-dump` was run and **the diff is reviewed in this PR**
- [ ] `CHANGELOG.md` has a line under `[Unreleased]` for anything a consumer can see
- [ ] Breaking changes sit under `### Breaking Changes` **and** have a `docs/guides/migration.md`
      section

## If this touches

<!-- Delete the lines that do not apply. Each of these is a gap no gate covers. -->

- **A `@Serializable` cache type** — v0.4.0 broke every Room entry this way and no check saw it.
  Treat it as a break, and say whether consumers need a cache-clear note.
- **`EnrichmentCacheDatabase`'s schema** — the `androidTest/` migration test must be written *and
  run on a device*, and said so here. Nothing in CI runs it, and a shipped migration is the one
  change a revert cannot undo.
- **A `musicmeta-core` dependency** — say why the floor moves. Every consumer inherits it
  transitively and it cannot be withdrawn without a break.

## Other notes

<!-- Anything not addressed here but that should be: a bug you found and left alone, something
     still needing research, a follow-up already planned. A reviewer who hits it anyway should
     find out here that you already know. -->

---

<!-- Please don't add "Generated with", Co-Authored-By, session links, or any other AI/tool
     attribution to this PR or its commits. CI fails a commit that carries one; this body is the
     half no gate reads. -->
