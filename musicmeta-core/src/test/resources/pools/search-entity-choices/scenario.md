# Search entity choices

## Provenance

Captured on 2026-11-08. `apostrophe-live.json` is the complete HTTP 200 response body from a
title-only MusicBrainz `/ws/2/recording` search for `Don’t Stop Believin’`, with `limit=100`.
The response body reports `created=2026-11-08T21:59:23.445Z`. The exact request URL and User-Agent
were not kept. Retained headers record `HTTP/2 200`, `date: Sun, 08 Nov 2026 21:59:23 GMT` and
`content-type: application/json`; no headers file is kept beside this pool.

The test selects the two unchanged Journey recordings from this response. Their straight and curly
apostrophe spellings are retained as returned.
