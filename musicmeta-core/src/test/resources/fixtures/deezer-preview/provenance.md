Captured 2026-11-07 from
https://api.deezer.com/search/track?q=Radiohead%20Karma%20Police&limit=5
with HTTP 200. The JSON body is unmodified.

DeezerBlockedTest replays HTTP 403 without a body, matching the host refusal status
reported by demo startup probes. No production refusal body was available to capture;
the classification must depend on HTTP status, including for non-JSON error pages.
The no-preview case removes only the preview URL from this successful response.
