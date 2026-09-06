#!/usr/bin/env bash
# Capture Discogs release-search pools for the frozen artist sample. One request per artist,
# per_page=100, paced at one every 1.5s (Discogs allows 60/min).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/captures"
mkdir -p "$out"
token="$(grep '^discogs.token=' /home/andy/dev/musicmeta/secrets.properties | cut -d= -f2-)"
while IFS=$'\t' read -r slug name; do
  [ -z "${slug:-}" ] && continue
  f="$out/$slug.json"
  [ -s "$f" ] && continue
  curl -sS -G "https://api.discogs.com/database/search" \
    --data-urlencode "type=release" \
    --data-urlencode "artist=$name" \
    --data-urlencode "per_page=100" \
    -H "Authorization: Discogs token=$token" \
    -H "User-Agent: musicmeta-probe/0.1" \
    -o "$f"
  echo "$slug $(wc -c <"$f")"
  sleep 1.5
done < "$here/artists.tsv"
