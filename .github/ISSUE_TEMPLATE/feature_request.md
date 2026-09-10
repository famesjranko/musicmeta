---
name: Feature request
about: Suggest a capability, a provider, or a change to an existing one
title: ''
labels: enhancement, needs-triage
---

## Problem / motivation

<!-- What you are trying to build, and what is missing or awkward today. The use case is more
     useful than the proposed API — it often has an answer the API shape would have hidden. -->

## Proposed change

<!-- What you would like musicmeta to do. -->

## Does it fit?

<!-- musicmeta resolves an identity once and then enriches it from many providers, keeping every
     type independent so one failure costs one type. ROADMAP.md says what is in scope and what
     1.0.0 is waiting on — a look there may answer this faster than waiting on a reply:
     https://github.com/famesjranko/musicmeta/blob/main/ROADMAP.md

     If your idea sits outside it, say why it is worth the exception; that is a real
     conversation, not a rejection. -->

## Compatibility

<!-- Optional — say what you know and leave the rest. -->

- [ ] This would need a change to the public API (`api/*.api`)
- [ ] This would change data already written to a consumer's cache

<!-- The project is pre-1.0: a minor may break with a migration-guide section, a patch may not.
     Knowing which of these applies early decides whether it waits for the next minor. -->

## New provider?

<!-- Only if you are proposing one. Providers set their own terms, and this library is used
     commercially, so this decides it before any code does: -->

- Provider and API docs:
- Does it require an API key?
- What do its terms say about commercial use, redistribution and attribution?
- What does it offer that an existing provider does not?

## Are you willing to contribute this?

No obligation either way; it just helps with planning.

- [ ] I'd like to implement this myself and open a pull request
- [ ] I'm suggesting the idea; happy for someone else to build it
