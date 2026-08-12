# musicmeta web demo

A standalone web app that renders everything the library exposes — artist, album, and track pages
with imagery, discovery, lyrics, and suggestions, plus the engine features: force refresh, cache
invalidation, the cache-mode toggle, search, and the provider/attribution panel. That claim is
audited, not asserted: the 2026-08-13 render-parity audit (`.scratch/demo-web-polish/issues/13`,
verified per `EnrichmentType` and per profile field, gaps fixed in PRs #192–#201) is its evidence.
Like `demo-cli/`, it is a composite build that consumes `musicmeta-core` the way an external app
would.

```bash
./run.sh                # http://localhost:8099
PORT=9000 ./run.sh      # different port
```

Works keyless. To enable the key-requiring providers, copy `secrets.properties.example` (repo root)
to `secrets.properties` here or in the repo root and fill in the keys, or set environment variables
(`LASTFM_API_KEY`, `FANARTTV_API_KEY`, `DISCOGS_TOKEN`, `LISTENBRAINZ_TOKEN`).
