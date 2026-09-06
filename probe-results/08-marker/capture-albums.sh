#!/usr/bin/env bash
# The five non-Latin album cells of the features/01 workload, fetched with the *production* query
# shape (`type=release&title=&artist=`, the URL `DiscogsApi.releaseSearchUrl` builds) rather than an
# artist-only browse — this is the pool the ticket's single sighting came from.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/captures"
mkdir -p "$out"
token="$(grep '^discogs.token=' /home/andy/dev/musicmeta/secrets.properties | cut -d= -f2-)"
while IFS=$'\t' read -r slug artist title; do
  [ -z "${slug:-}" ] && continue
  f="$out/$slug.json"
  [ -s "$f" ] && continue
  curl -sS -G "https://api.discogs.com/database/search" \
    --data-urlencode "type=release" \
    --data-urlencode "title=$title" \
    --data-urlencode "artist=$artist" \
    --data-urlencode "per_page=100" \
    -H "Authorization: Discogs token=$token" \
    -H "User-Agent: musicmeta-probe/0.1" \
    -o "$f"
  echo "$slug $(wc -c <"$f")"
  sleep 1.5
done < "$here/albums.tsv"
