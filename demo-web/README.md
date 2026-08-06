# musicmeta web demo

A standalone web app that renders everything the library exposes — artist, album, and track pages
with imagery, discovery, lyrics, and suggestions. Like `demo/`, it is a composite build that
consumes `musicmeta-core` the way an external app would.

```bash
./run.sh                # http://localhost:8099
PORT=9000 ./run.sh      # different port
```

Works keyless. To enable the key-requiring providers, create a `secrets.properties` here or in the
repo root, or set environment variables (`LASTFM_API_KEY`, `FANARTTV_API_KEY`, `DISCOGS_TOKEN`,
`LISTENBRAINZ_TOKEN`):

```properties
lastfm.apikey=...
fanarttv.apikey=...
discogs.token=...
listenbrainz.token=...
```
