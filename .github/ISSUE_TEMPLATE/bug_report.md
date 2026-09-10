---
name: Bug report
about: musicmeta returns something wrong, or does not return at all
title: ''
labels: bug, needs-triage
---

## What happened

<!-- The symptom, in plain terms. -->

## Expected vs actual

- Expected:
- Actual:

## Versions

- musicmeta version:
- Module(s): <!-- musicmeta-core / musicmeta-okhttp / musicmeta-android -->
- Platform: <!-- JVM (Java version), or Android (API level and device/emulator) -->
- Kotlin version:

## Which providers

<!-- Which provider(s) the failing data comes from, if you know — MusicBrainz, Cover Art Archive,
     Deezer, iTunes, LRCLIB, Wikidata, Wikipedia, ListenBrainz, Last.fm, Fanart.tv, Discogs. -->

- [ ] Running **keyless** (no API keys configured)
- [ ] Running with keys — which:
      <!-- Last.fm / Fanart.tv / Discogs / ListenBrainz user token -->
- [ ] A `contact()` / User-Agent is set
      <!-- MusicBrainz and Wikimedia throttle or block without one -->

## Reproduction

<!-- The smallest call that shows it. The artist/album/track matters — a lot of behaviour here
     depends on the specific upstream record. -->

```kotlin
val engine = EnrichmentEngine.Builder()
    .withDefaultProviders()
    .build()

// artistProfile() is a suspend fun — call it from a coroutine or runBlocking { }
val profile = runBlocking { engine.artistProfile("...") }
```

## Result or error

<!-- What came back. An EnrichmentResult carries a status and often a reason; paste it whole rather
     than summarising. If a type resolved as Error, NotFound or RateLimited, say which type. -->

> **Redact secrets first.** Remove API keys and `secrets.properties` values from anything you
> paste, including URLs — several providers put the key in the query string. Do not open a public
> issue for a vulnerability or a leaked credential; see
> [SECURITY.md](https://github.com/famesjranko/musicmeta/blob/main/SECURITY.md).

## Is it upstream?

<!-- Optional, and genuinely useful when you have it. This library reads eleven third-party APIs
     whose data changes without notice, so "the provider stopped returning that field" and
     "musicmeta stopped reading it" look identical from the outside. If you have checked the
     provider's own response — or the same lookup on their website — say what you saw. -->
