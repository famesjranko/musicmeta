# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root, or
- **`CONTEXT-MAP.md`** at the repo root if it exists — it points at one `CONTEXT.md` per context. Read each one relevant to the topic.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in. In multi-context repos, also check `src/<context>/docs/adr/` for context-scoped decisions.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

Neither `CONTEXT.md` nor `docs/adr/` exists yet. `CLAUDE.md` (the architecture map) and
`ARCHITECTURE.md` are the current sources of truth and should be read instead.

## How this sits with ARCHITECTURE.md

`ARCHITECTURE.md` is already this repo's register of which rules have a mechanism and which are
merely intended. An ADR records a **decision and its alternatives** — why MusicBrainz is the only
identity provider, why provider responses use `org.json` while payloads use `kotlinx.serialization`.
It does not restate a rule `ARCHITECTURE.md` already carries, and it never becomes a history
document: per `CLAUDE.md`, git and the PR hold what happened.

## File structure

Single-context repo (this repo):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-musicbrainz-as-sole-identity-provider.md
│   └── 0002-org-json-in-providers-kotlinx-in-payloads.md
└── musicmeta-core/
```

The three Gradle modules (`musicmeta-core`, `musicmeta-android`, `musicmeta-okhttp`) are one bounded
context — one engine, one set of domain terms — not separate contexts. If that ever stops being
true, a root `CONTEXT-MAP.md` pointing at per-module `CONTEXT.md` files is the multi-context layout:

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← system-wide decisions
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← context-specific decisions
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
