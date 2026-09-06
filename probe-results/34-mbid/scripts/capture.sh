#!/usr/bin/env bash
# Capture the held-out workload the same three ways the frozen twelve were captured.
#
# For each artist in artists.tsv (slug<TAB>name<TAB>musicbrainz mbid<TAB>deezer id):
#   lastfm.json   ws.audioscrobbler.com artist.getSimilar, limit 20 -- what LastFmApi asks for
#   labs.json     labs.api.listenbrainz.org similar-artists, the pinned algorithm constant
#   deezer.json   api.deezer.com /artist/{id}/related?limit=20
# Each body is written raw, with the exact request URL beside it in query.txt, and the date in
# captured-on.txt. Nothing is trimmed: a fixture that trims is a fixture that cannot be re-read.
#
# LASTFM_API_KEY=... ./heldout-capture.sh <out-dir>
set -uo pipefail
OUT="${1:?out dir}"
KEY="${LASTFM_API_KEY:?LASTFM_API_KEY required}"
HERE="$(cd "$(dirname "$0")" && pwd)"
UA="musicmeta-probe-34-lastfm-similar-mbid/0.1 ( andrewmcdonald42@gmail.com )"
ALGO="session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
date -u +%Y-%m-%d > "$OUT/captured-on.txt"

fetch() { # url outfile
  local u="$1" f="$2" t=1 code
  while [ "$t" -le 5 ]; do
    sleep 1.2
    code=$(curl -s -m 60 -A "$UA" -o "$f" -w '%{http_code}' "$u")
    [ "$code" = "200" ] && break
    sleep $((t * 3)); t=$((t+1))
  done
  echo "$code"
}

while IFS=$'\t' read -r SLUG NAME MBID DZID; do
  [ -n "${SLUG:-}" ] || continue
  case "$SLUG" in \#*) continue ;; esac
  D="$OUT/$SLUG"
  mkdir -p "$D"
  LF="https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar&artist=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$NAME")&api_key=${KEY}&format=json&limit=20"
  LB="https://labs.api.listenbrainz.org/similar-artists/json?artist_mbids=${MBID}&algorithm=${ALGO}"
  DZ="https://api.deezer.com/artist/${DZID}/related?limit=20"
  c1=$(fetch "$LF" "$D/lastfm.json")
  c2=$(fetch "$LB" "$D/labs.json")
  c3=$(fetch "$DZ" "$D/deezer.json")
  {
    echo "artist=$NAME"
    echo "musicbrainz=$MBID"
    echo "deezer=$DZID"
    echo "lastfm=${LF//$KEY/<LASTFM_API_KEY>}"
    echo "labs=$LB"
    echo "deezer_url=$DZ"
  } > "$D/query.txt"
  echo "$SLUG lastfm=$c1 labs=$c2 deezer=$c3"
done < "$HERE/artists.tsv"
