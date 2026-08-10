#!/usr/bin/env bash
# Whether a recording pool can be narrowed upstream instead of ranked downstream.
#
# The name-only track path resolves whatever MusicBrainz's relevance order puts in the top
# RECORDING_SEARCH_LIMIT. For a heavily-covered track that pool is all live/bootleg/cover takes, so
# pickBestRecording's blank-disambiguation tier has nothing to choose. Four questions:
#
#   1. Can the filter be expressed in the query at all? MB's recording index carries `comment`
#      (= disambiguation), `status` and `primarytype`, so `-comment:*` is exactly tier 4.
#   2. Does filtering alone fix it, or does it just make a different wrong answer look confident?
#   3. Where in the filtered pool does the canonical album cut actually land -- i.e. is this a
#      pool-composition problem or an ordering problem?
#   4. Does the filtered pool FIT? MB caps limit at 100 and does not clamp above it -- it silently
#      serves the default 25 -- so a filtered total over 100 cannot be taken in one request, and
#      "raise the limit" is not an escape hatch. The last row measures that ceiling directly.
#
# ALBUM is the release-group title the canonical recording should sit on; the probe reports where
# the first Official release on that release-group appears in each pool. Note what that does NOT
# mean: a disambiguated take (a demo, a remaster) can sit on the same release-group and match first,
# so read the `disamb=` field before calling a hit the studio original.
#
#   TITLE="Enter Sandman" ARTIST="Metallica" ALBUM="Metallica" ./scripts/probes/recording-pool-filter-probe.sh
#
# Not a gate: live third-party calls, and MB's order within a score tie is not stable across
# requests -- the same query at two limits can put the same recording inside and outside the window,
# and three runs of the identical query put it at index 19, 23 and 58. That instability is itself
# the finding. Re-run before citing any position from this script.
#
# Count failures by kind, not by track. One run of one title proves nothing about the shape of the
# fix: measured 2026-08-10, two of four tracks had a filtered pool larger than the 100 ceiling.
set -euo pipefail

UA="${PROBE_USER_AGENT:-musicmeta-probe/1.0 ( andrewmcdonald42@gmail.com )}"
BASE="https://musicbrainz.org/ws/2"
TITLE="${TITLE:-Enter Sandman}"
ARTIST="${ARTIST:-Metallica}"
ALBUM="${ALBUM:-Metallica}"
SPACING="${SPACING:-1.3}"

report() {
    local label="$1" query="$2" limit="$3"
    local encoded
    encoded="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$query")"
    curl -s -A "$UA" "$BASE/recording?query=$encoded&fmt=json&limit=$limit" > /tmp/recording-pool-probe.json
    python3 - "$label" "$ALBUM" <<'PY'
import json, sys
label, album = sys.argv[1], sys.argv[2]
d = json.load(open('/tmp/recording-pool-probe.json'))
if 'error' in d:
    sys.exit(f'  {label} -> UPSTREAM ERROR, this run proves nothing: ' + d['error'][:160])
recs = d.get('recordings', [])
blank = [r for r in recs if not (r.get('disambiguation') or '').strip()]
canonical = None
for i, r in enumerate(recs):
    for rel in r.get('releases', []):
        group = rel.get('release-group') or {}
        if group.get('title') == album and group.get('primary-type') == 'Album' \
                and rel.get('status') == 'Official':
            canonical = (i, r['id'], r.get('length'), r.get('disambiguation'))
            break
    if canonical:
        break
where = (f'index {canonical[0]} ({canonical[1][:8]}, len={canonical[2]}, disamb={canonical[3]!r})'
         if canonical else 'NOT IN THIS POOL')
total, returned = d.get('count'), len(recs)
fits = '' if not isinstance(total, int) else ('' if total <= returned else f'  OVERFLOW: {total - returned} dropped')
print(f'  {label}')
print(f'      total={total} returned={returned} blank-disambiguation={len(blank)}{fits}')
print(f'      canonical cut on Official Album "{album}": {where}')
PY
    sleep "$SPACING"
}

BASE_Q="recording:\"$TITLE\" AND artistname:\"$ARTIST\""
HINT_Q="$BASE_Q AND release:\"$ALBUM\""

echo "== production query today (RECORDING_SEARCH_LIMIT=25) =="
report "as shipped, no album hint " "$BASE_Q" 25
report "as shipped, album hint    " "$HINT_Q" 25

echo
echo "== is the tier-4 filter expressible in the query? =="
report "-comment:* , no hint, 25  " "$BASE_Q AND -comment:*" 25
report "-comment:* , no hint, 100 " "$BASE_Q AND -comment:*" 100
report "-comment:* , album hint   " "$HINT_Q AND -comment:*" 100

echo
echo "== is 100 really the ceiling? =="
report "-comment:* , limit=200    " "$BASE_Q AND -comment:*" 200

cat <<EOF

Measured $(date -u '+%Y-%m-%d %H:%MZ'), TITLE="$TITLE" ARTIST="$ARTIST" ALBUM="$ALBUM".

Read the rows against each other, not alone:
  * the two -comment:* no-hint rows: canonical absent at 25 and present at 100 within the SAME total
    means the pool was never the problem -- the tie ordering is, and no small fixed limit is safe.
  * OVERFLOW on the limit=100 row: the filter did not bring this track's pool under MB's ceiling, so
    the tail is unreachable in one request whatever limit the code asks for.
  * the limit=200 row: if it returns 25, MB ignored the limit rather than clamping it, and any
    future "just raise it" edit shrinks the pool silently.
One run is a sample; re-run before citing a position, and run several tracks before citing a shape.
EOF
