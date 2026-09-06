#!/usr/bin/env bash
# Does the marker ever reach the *artist* paths? One `type=artist` search per non-Latin sample name,
# so the claim "only credits carry it" is measured rather than assumed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/captures-artist"
mkdir -p "$out"
token="$(grep '^discogs.token=' /home/andy/dev/musicmeta/secrets.properties | cut -d= -f2-)"
while IFS=$'\t' read -r slug name; do
  [ -z "${slug:-}" ] && continue
  f="$out/$slug.json"
  [ -s "$f" ] && continue
  curl -sS -G "https://api.discogs.com/database/search" \
    --data-urlencode "type=artist" \
    --data-urlencode "q=$name" \
    --data-urlencode "per_page=100" \
    -H "Authorization: Discogs token=$token" \
    -H "User-Agent: musicmeta-probe/0.1" \
    -o "$f"
  echo "$slug $(wc -c <"$f")"
  sleep 1.5
done < "$here/artists.tsv"
