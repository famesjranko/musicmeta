#!/usr/bin/env bash
# How many MusicBrainz artists carry the name a Last.fm similar row carries?
#
# A row whose supplied MBID has no last.fm relation cannot be adjudicated from that id alone: an
# absent relation is MusicBrainz's silence, not a verdict. But if MusicBrainz holds exactly one
# artist under that name, no *other* act can be the one the row's last.fm page belongs to, and the
# id is uncontested. Only a contested name needs the per-candidate url-rels lookup that follows.
#
# Usage: NAME_FILE=<file of names, one per line> ./namesearch.sh <out-dir>
set -uo pipefail
OUT="${1:?out dir}"
NAME_FILE="${NAME_FILE:?file of names}"
SPACING="${SPACING:-1.6}"
TRIES="${TRIES:-6}"
mkdir -p "$OUT"
UA="musicmeta-probe-34-lastfm-similar-mbid/0.1 ( andrewmcdonald42@gmail.com )"
i=0
while IFS= read -r N; do
  [ -n "$N" ] || continue
  i=$((i+1))
  SLUG=$(printf '%04d' "$i")
  if [ -f "$OUT/${SLUG}.status" ]; then continue; fi
  Q=$(python3 -c 'import sys,urllib.parse;n=sys.argv[1].replace("\\","\\\\").replace("\"","\\\"");print(urllib.parse.quote("artist:\""+n+"\" OR alias:\""+n+"\"",safe=""))' "$N")
  t=1
  while [ "$t" -le "$TRIES" ]; do
    sleep "$SPACING"
    code=$(curl -s -m 40 -A "$UA" -o "$OUT/${SLUG}.json" -w '%{http_code}' \
      "https://musicbrainz.org/ws/2/artist?query=${Q}&fmt=json&limit=25")
    case "$code" in
      200|404) break ;;
      *) sleep $((t * 3)); t=$((t+1)) ;;
    esac
  done
  echo "$code" > "$OUT/${SLUG}.status"
  printf '%s\t%s\n' "$SLUG" "$N" >> "$OUT/index.tsv"
  echo "$SLUG $code $N"
done < "$NAME_FILE"
echo "name-searched on $(date -u +%Y-%m-%d)" >&2
