#!/usr/bin/env bash
# What a caller-supplied recording MBID is actually worth, when the caller is Last.fm.
#
# enrichTrackByMbid treats an MBID on a ForTrack request as the recording the answer must describe,
# and a lookup miss as NotFound for every MusicBrainz track type -- never a fall back to the name
# search, because answering with a *different* recording is the defect that path closes. That is the
# right trade only if a supplied MBID is usually good. Last.fm is where these come from in practice
# (trackProfile() on a similar-track or radio pick carries a recording MBID and nothing else), and
# Last.fm's ids were bulk-imported and are not re-synced. So the question is empirical, and it has
# three answers, not two:
#
#   1. HIT      - MusicBrainz holds it. The lookup path is worth taking.
#   2. REDIRECT - merged away, and MusicBrainz 301s to the survivor. DefaultHttpClient follows
#                 redirects on this path (only the Cover Art Archive path disables that), so the
#                 caller never sees it. Counts as a hit; MergedRecordingMbidE2ETest pins one.
#   3. GONE     - MusicBrainz does not hold it under any entity type. This is the case that costs a
#                 consumer the whole track, and the reason that NotFound carries suggestions.
#
# Read GONE against the UUID version, which the summary breaks out. MusicBrainz mints v4 (random)
# UUIDs; a v3 (name-based) id in Last.fm's mbid field was never a MusicBrainz identifier at all, so
# counting the two together overstates how often MusicBrainz *lost* something. Both still reach our
# code as "a caller supplied an MBID", so both belong in the total -- they just answer different
# questions.
#
#   LASTFM_API_KEY=... ./scripts/probes/lastfm-mbid-staleness-probe.sh
#   LASTFM_API_KEY=... LIMIT=200 SOURCE=chart ./scripts/probes/lastfm-mbid-staleness-probe.sh
#
# SOURCE=chart uses chart.getTopTracks (one request, current charts). SOURCE=artist uses
# artist.getTopTracks over ARTISTS, which reaches older catalogue and so an older import.
# SOURCE=similar walks track.getSimilar from each artist's top five, which is the population
# trackProfile() meets in the motivating scenario -- a radio or similar-track pick, where the caller
# holds a recording MBID and nothing else. Read the three against each other, not on their own: the
# rate tracks how obscure the track is, so a single number without its population is meaningless.
#
# Not a gate, and it must never become one: live third-party calls on both sides, so it cannot decide
# whether this repo's code is correct. It is a measuring instrument. Run it, paste the summary into
# whatever claim needs backing, and date it -- the numbers move as MusicBrainz merges and as Last.fm
# refreshes (rarely). MusicBrainz sheds with 503 under load; those are counted separately and are not
# evidence of anything except that it was busy.
set -uo pipefail

KEY="${LASTFM_API_KEY:-}"
[ -n "$KEY" ] || { echo "LASTFM_API_KEY is required" >&2; exit 2; }
LIMIT="${LIMIT:-500}"
SOURCE="${SOURCE:-chart}"
ARTISTS="${ARTISTS:-Metallica|Pink Floyd|Radiohead|The Beatles|Queen}"
ARTIST_COUNT=$(printf '%s\n' "$ARTISTS" | tr '|' '\n' | grep -c .)
UA="${UA:-musicmeta-probe/1.0 ( https://github.com/famesjranko/musicmeta )}"
MB_SPACING="${MB_SPACING:-1.05}"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

collect_ids() {
  if [ "$SOURCE" = "similar" ]; then
    # The population trackProfile() actually meets. A similar-track pick is where a caller has a
    # recording MBID and nothing else, and Last.fm draws these from further down its catalogue than
    # either chart does -- which is the whole question, since staleness tracks obscurity.
    printf '%s\n' "$ARTISTS" | tr '|' '\n' | { n=0; while IFS= read -r a; do
      [ -n "$a" ] || continue
      n=$((n+1))
      sleep 0.25
      seeds=$(curl -sS -A "$UA" -G "https://ws.audioscrobbler.com/2.0/" \
        --data-urlencode "method=artist.gettoptracks" --data-urlencode "artist=$a" \
        --data-urlencode "api_key=$KEY" --data-urlencode "format=json" --data-urlencode "limit=5" \
        | python3 -c 'import json,sys;d=json.load(sys.stdin);print("\n".join(t["name"] for t in d.get("toptracks",{}).get("track",[])))')
      printf '%s\n' "$seeds" | while IFS= read -r t; do
        [ -n "$t" ] || continue
        sleep 0.25
        curl -sS -A "$UA" -G "https://ws.audioscrobbler.com/2.0/" \
          --data-urlencode "method=track.getsimilar" --data-urlencode "artist=$a" \
          --data-urlencode "track=$t" --data-urlencode "api_key=$KEY" \
          --data-urlencode "format=json" --data-urlencode "limit=$LIMIT" \
          | python3 -c 'import json,sys;d=json.load(sys.stdin);print("\n".join(t["mbid"] for t in d.get("similartracks",{}).get("track",[]) if t.get("mbid")))'
      done
    done; echo "probed $n of $ARTIST_COUNT artist(s)" >&2; }
  elif [ "$SOURCE" = "artist" ]; then
    printf '%s\n' "$ARTISTS" | tr '|' '\n' | { n=0; while IFS= read -r a; do
      [ -n "$a" ] || continue
      n=$((n+1))
      sleep 0.25
      curl -sS -A "$UA" -G "https://ws.audioscrobbler.com/2.0/" \
        --data-urlencode "method=artist.gettoptracks" --data-urlencode "artist=$a" \
        --data-urlencode "api_key=$KEY" --data-urlencode "format=json" \
        --data-urlencode "limit=$LIMIT" \
        | python3 -c 'import json,sys;d=json.load(sys.stdin);print("\n".join(t["mbid"] for t in d.get("toptracks",{}).get("track",[]) if t.get("mbid")))'
    done; echo "probed $n of $ARTIST_COUNT artist(s)" >&2; }
  else
    curl -sS -A "$UA" -G "https://ws.audioscrobbler.com/2.0/" \
      --data-urlencode "method=chart.gettoptracks" --data-urlencode "api_key=$KEY" \
      --data-urlencode "format=json" --data-urlencode "limit=$LIMIT" \
      | python3 -c 'import json,sys;d=json.load(sys.stdin);print("\n".join(t["mbid"] for t in d.get("tracks",{}).get("track",[]) if t.get("mbid")))'
  fi
}

collect_ids | grep -E '^[0-9a-f-]{36}$' | sort -u > "$tmp/ids"
total=$(wc -l < "$tmp/ids" | tr -d ' ')
echo "source=$SOURCE limit=$LIMIT  unique MBIDs from Last.fm: $total"
echo

hit=0; redirect=0; gone=0; shed=0; other=0; gone3=0; gone4=0
while IFS= read -r id; do
  sleep "$MB_SPACING"
  hdr=$(curl -sS --max-time 25 -A "$UA" -o "$tmp/body" -D - -w '%{http_code}' \
    "https://musicbrainz.org/ws/2/recording/$id?fmt=json" 2>/dev/null)
  code=$(printf '%s' "$hdr" | tail -1)
  loc=$(printf '%s' "$hdr" | grep -i '^location:' | tr -d '\r' | sed 's/^[Ll]ocation: *//')
  case "$code" in
    200) hit=$((hit+1)) ;;
    301|302|307|308)
      redirect=$((redirect+1))
      echo "REDIRECT $id -> $(printf '%s' "$loc" | sed 's|.*/recording/||; s|?.*||')" ;;
    404)
      gone=$((gone+1))
      case "$id" in ????????-????-3*) gone3=$((gone3+1)) ;; *) gone4=$((gone4+1)) ;; esac ;;
    503) shed=$((shed+1)) ;;
    *) other=$((other+1)); echo "OTHER $id -> $code" ;;
  esac
done < "$tmp/ids"

answered=$((hit+redirect+gone))
echo
echo "=== summary  (probed=$total, MusicBrainz answered=$answered, shed 503=$shed, other=$other)"
echo "HIT           $hit"
echo "REDIRECT      $redirect   merged away, followed transparently -- still usable"
echo "GONE          $gone   not held under any entity type -- the NotFound path"
echo "  of which v3 $gone3   name-based UUID: never a MusicBrainz id"
echo "  of which v4 $gone4   MusicBrainz-shaped: an id it no longer holds"
[ "$answered" -gt 0 ] && echo "usable share  $(( 100 * (hit + redirect) / answered ))%  of the ids MusicBrainz answered for"
echo
echo "Date this before quoting it. A GONE id is what a consumer loses the whole track to."
