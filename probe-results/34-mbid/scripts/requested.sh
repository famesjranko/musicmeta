#!/usr/bin/env bash
# The requested artist's own MusicBrainz record, for each workload artist.
#
# This is the lookup `MusicBrainzApi.lookupArtistWithRels` already makes on every artist enrich, so
# what it carries is free to arm C: the artist's curated genres and its vote-carrying tags. The id
# is not resolved here -- it is read off the Labs fixture's `reference_mbid`, which echoes back the
# artist the similar-artists request named, so no search is spent finding it.
set -uo pipefail
FIXDIR="${1:?fixture dir}"
OUT="${2:?out dir}"
mkdir -p "$OUT"
UA="musicmeta-probe-34-lastfm-similar-mbid/0.1 ( andrewmcdonald42@gmail.com )"
for d in "$FIXDIR"/*/; do
  slug=$(basename "$d")
  mbid=$(python3 -c 'import json,sys;j=json.load(open(sys.argv[1]));print(j[0]["reference_mbid"] if j else "")' "$d/labs.json")
  [ -n "$mbid" ] || { echo "$slug NO-REFERENCE-MBID"; continue; }
  [ -s "$OUT/$slug.json" ] && grep -q '"id"' "$OUT/$slug.json" && continue
  t=1
  while [ "$t" -le 6 ]; do
    sleep 1.6
    code=$(curl -s -m 40 -A "$UA" -o "$OUT/$slug.json" -w '%{http_code}' \
      "https://musicbrainz.org/ws/2/artist/${mbid}?fmt=json&inc=tags+genres+url-rels")
    case "$code" in 200|404) break ;; *) sleep $((t * 3)); t=$((t+1)) ;; esac
  done
  echo "$slug $mbid $code"
done
