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

`DEMO_PUBLIC_ALLOW` lifts individual restrictions on a public instance, comma separated:
`lastfm` (register Last.fm and pass its key), `listenbrainz` (pass the personal token),
`discogs-images` (serve Discogs images), `discogs-cache` (drop the six-hour ceiling), or `all`.
Each one is a deliberate choice to take on that provider's terms yourself, and the startup log
names what was lifted alongside what still binds. A token that names no restriction refuses the
start rather than being ignored, so a typo cannot quietly leave a posture nobody chose. With
`DEMO_PUBLIC` unset the variable does nothing at all, typos included.

Under `DEMO_PUBLIC`, changing the running cache mode (the settings-panel radios, `POST
/api/config`) needs `DEMO_MAINTAINER_SECRET` set, and the request must carry it back as the
`X-Maintainer-Secret` header — append `?maintainer=<the secret>` to the page URL and the panel
sends it for you. With no `DEMO_MAINTAINER_SECRET` set, every such change is refused: a public
instance has no unauthenticated control over its own running configuration. `GET /api/config`
(what the page reads on load) stays open under every posture. With `DEMO_PUBLIC` unset the panel
works with no secret at all, exactly as before this existed.

`/api/health` is a **liveness** probe, not a readiness one: it reports that the process is
accepting requests, nothing about whether any upstream provider is reachable. Do not wire an
uptime alert to it and expect it to flap on a provider outage — it won't, by design.
