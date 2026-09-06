#!/usr/bin/env bash
# Who owns the last.fm page a Last.fm similar row links to?
#
# classify.py leaves a row UNKNOWN when the MBID it carries has no last.fm url relation of its own:
# absence of a relation is not evidence the id is wrong. This asks MusicBrainz the reverse question
# -- /ws/2/url?resource=<page>&inc=artist-rels names every artist that page is related to -- which
# is what actually adjudicates the row. A 404 means MusicBrainz has never seen that page, and the
# row stays unadjudicated.
#
# Usage: URL_FILE=<file of last.fm urls, one per line> ./reverse.sh <out-dir>
set -uo pipefail
OUT="${1:?out dir}"
URL_FILE="${URL_FILE:?file of urls}"
SPACING="${SPACING:-1.6}"
TRIES="${TRIES:-6}"
mkdir -p "$OUT"
UA="musicmeta-probe-34-lastfm-similar-mbid/0.1 ( andrewmcdonald42@gmail.com )"
while IFS= read -r U; do
  [ -n "$U" ] || continue
  SLUG=$(printf '%s' "$U" | sed 's|.*/music/||' | tr -c 'A-Za-z0-9._-' '_')
  if [ -f "$OUT/${SLUG}.status" ]; then continue; fi
  ENC=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$U")
  t=1
  while [ "$t" -le "$TRIES" ]; do
    sleep "$SPACING"
    code=$(curl -s -m 40 -A "$UA" \
      -o "$OUT/${SLUG}.json" -w '%{http_code}' \
      "https://musicbrainz.org/ws/2/url?resource=${ENC}&inc=artist-rels&fmt=json")
    case "$code" in
      200|404) break ;;
      *) sleep $((t * 3)); t=$((t+1)) ;;
    esac
  done
  echo "$code" > "$OUT/${SLUG}.status"
  echo "$SLUG $code $U"
done < "$URL_FILE"
echo "reverse-captured on $(date -u +%Y-%m-%d)" >&2
