# Contributing

Thanks for taking the time to improve musicmeta. The thing to keep in mind is what the library
promises a consumer: an identity resolved once, then enriched from eleven providers, with every
enrichment type independent so one provider failing costs that type and nothing else.

This project is pre-1.0 and published to Maven Central and JitPack, so assume external consumers
exist and a break reaches them.

## Start here

For anything beyond a small, obvious fix, open an issue first so the approach can be agreed before
you spend time on code. Bug reports and feature requests each have a template. Typos and one-line
corrections can go straight to a pull request.

Filing an issue does not commit you to writing the code — the feature-request template has a
checkbox for saying which you mean. If you want to implement it, comment to claim it.

**Where issues live.** GitHub Issues is the inbound door: it is where you report a bug or propose a
change, and where that conversation happens. The maintainer's own working tracker is a set of local
markdown files that are not in this repository, so an issue you file may be mirrored there once it
is triaged — you will see the outcome on your issue either way.

## Getting set up

```
make bootstrap    # installs the pinned tools ./check needs — once per machine
make check        # everything CI runs
```

`make help` lists the rest. `make check-fast` is for the edit loop only and is never evidence for a
push; `check`'s own header says what it skips.

Build on **Java 17**, not a later JDK. The published artifacts target 17 and both CI jobs run on it,
but Kotlin resolves JDK classes from whichever JDK is running, so a JDK 18+ API compiles cleanly
on your machine and fails in CI. `musicmeta-core/build.gradle.kts` records why the toolchain is not
pinned for you.

`make check` also needs **Node 22 or newer** (the demo's browser tests run on node's own test
runner; `bootstrap.sh` deliberately does not install it) and an **Android SDK** at `ANDROID_HOME` or
`~/Android/Sdk`, because the build compiles `musicmeta-android`. Without either, `check` refuses to
start rather than skipping the layer.

You do not need API keys: eight of the eleven providers work without them, and the test suite
needs none at all.

To try a change from a real consumer before publishing, add `includeBuild("../musicmeta")` to that
project's `settings.gradle.kts`; [docs/project/workflow.md](docs/project/workflow.md) covers the
alternatives.

## Branches and merging

`main` is the only permanent branch and is protected: a pull request is required, `build` and
`demo-canary` must pass, and history is linear. Work on a short-lived branch and open a PR into
`main`.

**PRs are squash-merged.** The PR title becomes the commit subject; the body of the squash commit is
built from your **commit messages**, not from the PR description. So write the commit message for
the person who lands on it from `git blame` in six months — the PR description is for the reviewer
today, and it does not survive the merge.

## What a pull request needs

The template walks through this. Three things are worth stating up front because they are unusual,
and because no tool checks them:

**A test is watched fail before the code that makes it pass.** A test first seen green proves that
its assertions run, not that they could catch anything. So the claim carries its own evidence: name
the mutation you made and the test that went red, or paste the failure. This is the single rule most
likely to come back in review.

**A provider fixture must predate the change it is evidence for.** Fixtures are captured from real
upstream responses. One written to match new code proves only that the code agrees with itself.

**Breaking changes are allowed in a minor, but only visibly.** A break must appear under a
`### Breaking Changes` heading in `CHANGELOG.md` *and* in the reviewed `api/*.api` diff; a break in
neither is a defect. A patch may not break at all. In practice a break also gets a section in
[docs/guides/migration.md](docs/guides/migration.md) telling a consumer what edit it asks of
them — no check can tell whether an individual break has one, so it is worth writing while the
change is still in your head. If you think your change breaks something, say so in the PR rather
than deciding alone: some of it is not obvious, and a JVM descriptor can make an innocent-looking
edit binary-incompatible.

Two changes carry a cost that is invisible in the diff, so flag them explicitly: a `@Serializable`
cache type (no gate reads these, and v0.4.0 broke every cached entry that way) and
`EnrichmentCacheDatabase`'s schema (nothing in CI runs the device test, and a shipped migration is
the one change a revert cannot undo).

## Adding a provider

A new provider is `provider/<name>/` as `*Api`, `*Models` and `*Mapper` — all `internal` — plus
a public `*Provider`. Keeping the first three internal is what lets them be renamed later without an
`apiDump`.

Open an issue first. Providers set their own terms on commercial use, redistribution and
attribution, and this library is used commercially, so the terms decide it before any code does.
[docs/providers.md](docs/providers.md) records those terms per provider, and
[ARCHITECTURE.md](ARCHITECTURE.md) says what a provider costs to add.

## Style

Run `make format`. Beyond that, two conventions that reviews do enforce:

- **Comments carry the contract, not the history.** No PR or issue numbers, no "previously
  we…" — git and the PR hold those. A comment that restates the code under it is a defect.
- **Kotlin test bodies use `// Given -` / `// When -` / `// Then -`**, each on its own line with a
  real clause. This one *is* mechanised, by `scripts/checks/check_test_shape.py`.

Please don't add `Co-Authored-By`, "Generated with", session links, or any other AI or tool
attribution to commits, PR titles or bodies, or issue comments.

## Review

A maintainer reviews each pull request and may ask you to split a large change into smaller pieces.
A request for changes is about the code, not about you.

## Reporting a vulnerability

Not through a public issue — see [SECURITY.md](SECURITY.md).

## Contribution licensing

By submitting a pull request, patch, issue comment, documentation change, or other contribution to
musicmeta, you agree that your contribution is provided under the project's licence, the
[Apache License 2.0](LICENSE), and that you have the right to submit it.

If your contribution includes third-party material, identify it in the pull request with its licence
and source. Provider data is a special case of this: what an upstream API returns comes with that
provider's terms attached, and those terms are recorded in
[docs/providers.md](docs/providers.md#terms-licences-attribution).

## Where the writing goes

A finding has one home, so a note does not end up in three files or none. If your change taught you
something, put it in exactly one of these and say which in the PR:

| A new | Goes in |
|---|---|
| Trap that cost something | [docs/pitfalls.md](docs/pitfalls.md) |
| Consumer-visible change | a [CHANGELOG.md](CHANGELOG.md) line — its header defines the shape |
| Fact about the tooling — a gate that does not exist | [VERIFICATION.md](VERIFICATION.md), "Known gaps" |

## Further reading

- [docs/project/workflow.md](docs/project/workflow.md) — branch topology, worktrees, verification
- [ARCHITECTURE.md](ARCHITECTURE.md) — module boundaries, `enrich()`, what a provider costs
- [VERIFICATION.md](VERIFICATION.md) — what a green run does and does not prove
- [docs/glossary.md](docs/glossary.md) — one word per concept, and each upstream's word for it
