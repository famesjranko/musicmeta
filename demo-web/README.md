# musicmeta web demo

A standalone web app that renders everything the library exposes — artist, album, and track pages
with imagery, discovery, lyrics, and suggestions, plus the engine features: force refresh, cache
invalidation, the cache-mode toggle, search, and the provider/attribution panel. That claim is
audited, not asserted: the 2026-08-13 render-parity audit (verified per `EnrichmentType` and per
profile field, gaps fixed in PRs #192–#201) is its evidence.
Like `demo-cli/`, it is a composite build that consumes `musicmeta-core` the way an external app
would.

```bash
./run.sh                # http://localhost:8099
PORT=9000 ./run.sh      # different port
```

Works keyless. To enable the key-requiring providers, copy `secrets.properties.example` (repo root)
to `secrets.properties` here or in the repo root and fill in the keys, or set environment variables
(`LASTFM_API_KEY`, `FANARTTV_API_KEY`, `DISCOGS_TOKEN`, `LISTENBRAINZ_TOKEN`).

Every card credits the upstreams that filled it, built from the provenance each response carries —
never from a guess about which provider serves what — and links back to the upstream's own page for
that entity where the response resolved an identifier for one. Credits are ungated: they cost a
local run nothing and crediting providers is the point. No third-party logo is bundled: Apple's
badge needs its Web Badge licence agreement and Deezer's logo its unpublished Trademark Guidelines,
so each provider is credited in text until those are accepted and an asset can be added.

`DEMO_PUBLIC=1` puts the process in the posture a publicly reachable instance owes its providers,
whatever keys the environment carries: Last.fm is not registered, the ListenBrainz personal token
is withheld, Discogs images never leave the process, and Discogs-sourced data is refetched every
six hours. Unset — every local run — nothing above applies. The startup log states the posture.
