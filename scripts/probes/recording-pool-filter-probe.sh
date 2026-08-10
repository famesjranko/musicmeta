#!/usr/bin/env bash
# Backs the decision in .scratch/track-mbid-honoured/issues/03-recording-search-limit-no-longer-holds-a-studio-candidate.md.
#
# The name-only track path resolves whatever MusicBrainz's relevance order puts in the top
# RECORDING_SEARCH_LIMIT. For a heavily-covered track that pool is all live/bootleg/cover takes, so
# pickBestRecording's blank-disambiguation tier has nothing to choose. Three questions:
#
#   1. Can the filter be expressed in the query at all? MB's recording index carries `comment`
#      (= disambiguation), `status` and `primarytype`, so `-comment:*` is exactly tier 4.
#   2. Does filtering alone fix it, or does it just make a different wrong answer look confident?
#   3. Where in the filtered pool does the canonical album cut actually land — i.e. is this a
#      pool-composition problem or an ordering problem?
#
# ALBUM is the release-group title the canonical recording should sit on; the probe reports where
# the first Official release on that release-group appears in each pool.
#
#   TITLE="Enter Sandman" ARTIST="Metallica" ALBUM="Metallica" ./scripts/probes/recording-pool-filter-probe.sh
#
# Not a gate: live third-party calls, and MB's order within a score tie is not stable across
# requests — the same query at two limits can put the same recording inside and outside the window.
# That instability is itself the finding. Re-run before citing any position from this script.
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
            canonical = (i, r['id'], r.get('length'))
            break
    if canonical:
        break
where = f'index {canonical[0]} ({canonical[1]}, len={canonical[2]})' if canonical else 'NOT IN THIS POOL'
print(f'  {label}')
print(f'      total={d.get("count")} returned={len(recs)} blank-disambiguation={len(blank)}')
print(f'      canonical cut on Official Album "{album}": {where}')
PY
    sleep "$SPACING"
}

echo "== production query today (RECORDING_SEARCH_LIMIT=25) =="
report "as shipped" "recording:\"$TITLE\" AND artistname:\"$ARTIST\"" 25

echo
echo "== is the tier-4 filter expressible in the query? =="
report "-comment:* , limit=25 " "recording:\"$TITLE\" AND artistname:\"$ARTIST\" AND -comment:*" 25
report "-comment:* , limit=100" "recording:\"$TITLE\" AND artistname:\"$ARTIST\" AND -comment:*" 100

cat <<EOF

Measured $(date -u '+%Y-%m-%d %H:%MZ'), TITLE="$TITLE" ARTIST="$ARTIST" ALBUM="$ALBUM".
Read the two -comment:* rows together: if the canonical cut is absent at limit=25 and present at
limit=100 within the SAME total, the pool is not the problem -- the tie ordering is, and no fixed
limit is safe. One run is a sample; re-run before citing a position.
EOF
