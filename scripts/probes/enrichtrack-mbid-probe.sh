#!/usr/bin/env bash
# What a recording search returns against what a recording lookup returns.
#
# Three live questions, none answerable from the code alone:
#
#   1. How deep is the tied-score pool for a heavily-covered track, and does RECORDING_SEARCH_LIMIT
#      surface a blank-disambiguation (studio) candidate within it? Any claim that it does is a
#      measurement, and a measurement decays as MusicBrainz catalogues more live recordings.
#   2. Does lookupRecording's inc= omit `releases`, and does widening it to include
#      releases+release-groups recover them, each carrying a release-group?
#   3. When it does, does the recovered `releases` array embed in the SAME ORDER a search hit would —
#      findArtReleaseGroup's tiers 1-3 pick the FIRST match within a tier, so order can change which
#      release-group (and so which cover art) a request resolves to.
#
# Not a gate: live third-party calls, answers drift as MusicBrainz's catalogue grows (new live
# recordings are added constantly for touring bands). Run it, paste the block into whatever claim
# needs backing, and date it. One run is a sample — MB's own tie order shifts with the limit
# itself — so re-run before trusting a single result.
#
#   TITLE="Enter Sandman" ARTIST="Metallica" ./scripts/probes/enrichtrack-mbid-probe.sh
#
set -euo pipefail

UA="${PROBE_USER_AGENT:-musicmeta-probe/1.0 ( andrewmcdonald42@gmail.com )}"
BASE="https://musicbrainz.org/ws/2"
TITLE="${TITLE:-Enter Sandman}"
ARTIST="${ARTIST:-Metallica}"
LIMIT="${LIMIT:-25}"
SPACING="${SPACING:-1.3}"

encoded_query() {
    python3 -c "
import urllib.parse
print(urllib.parse.quote('recording:\"$1\" AND artistname:\"$2\"'))
"
}

echo "== 1. pool depth: how many total ties, how many of the top $LIMIT are blank-disambiguation =="
Q="$(encoded_query "$TITLE" "$ARTIST")"
curl -s -A "$UA" "$BASE/recording?query=$Q&fmt=json&limit=$LIMIT" > /tmp/enrichtrack-probe-pool.json
python3 -c "
import json
d = json.load(open('/tmp/enrichtrack-probe-pool.json'))
blanks = [r for r in d.get('recordings', []) if not (r.get('disambiguation') or '').strip()]
print(f\"count(total tied)={d.get('count')}  returned={len(d.get('recordings', []))}  blank-disambiguation-in-pool={len(blanks)}\")
for r in blanks:
    print('  BLANK:', r['id'])
"
sleep "$SPACING"

echo
echo "== 2. field loss: lookupRecording as coded today (inc=artist-rels+work-rels) vs releases+release-groups =="
# Reuse a recording ID from step 1's own $LIMIT pool, not a fresh query -- MB's tied-score order
# shifts between separate calls (even with identical params), so a second query's limit=1 hit is
# not guaranteed to be a member of this pool, and step 3 needs it to be.
MBID="$(python3 -c "import json; print(json.load(open('/tmp/enrichtrack-probe-pool.json'))['recordings'][0]['id'])")"
echo "probing recording $MBID"
TODAY_KEYS="$(curl -s -A "$UA" "$BASE/recording/$MBID?fmt=json&inc=artist-rels+work-rels" | python3 -c "import json,sys; print(sorted(json.load(sys.stdin).keys()))")"
echo "  inc=artist-rels+work-rels (today's code) top-level keys: $TODAY_KEYS"
sleep "$SPACING"
curl -s -A "$UA" "$BASE/recording/$MBID?fmt=json&inc=artist-rels+work-rels+releases+release-groups" > /tmp/enrichtrack-probe-widened.json
# MB answers a shed request or a rejected inc= with a 200-shaped {"error": ...} body. Without this
# guard step 3 compares two empty lists and reports "same tier-1 pick: True" — a pass built on no
# data. Seen 2026-08-10: one run errored here and step 3 printed a clean-looking result anyway.
WIDENED_KEYS="$(python3 -c "
import json, sys
d = json.load(open('/tmp/enrichtrack-probe-widened.json'))
if 'error' in d:
    sys.exit('  UPSTREAM ERROR, this run proves nothing -- re-run: ' + d['error'][:160])
print(sorted(d.keys()))
")"
echo "  inc=artist-rels+work-rels+releases+release-groups top-level keys: $WIDENED_KEYS"
sleep "$SPACING"

echo
echo "== 3. embedding order: search-hit releases[] vs lookup releases[] for the same recording =="
python3 -c "
import json
pool = json.load(open('/tmp/enrichtrack-probe-pool.json'))
lookup = json.load(open('/tmp/enrichtrack-probe-widened.json'))
hit = next((r for r in pool['recordings'] if r['id'] == '$MBID'), None)
if hit is None:
    print('  target recording fell outside the search pool at this limit -- cannot compare order')
else:
    search_order = [r['id'] for r in hit.get('releases', [])]
    lookup_order = [r['id'] for r in lookup.get('releases', [])]
    print('  same release set:', set(search_order) == set(lookup_order))
    print('  same order:      ', search_order == lookup_order)

    # Tier 1 as MusicBrainzParser.findArtReleaseGroup actually applies it: the FIRST release in
    # array order that is both status=Official and release-group primary-type=Album. Comparing the
    # first element of each array instead would answer a question the production code never asks.
    by_id = {r['id']: r for r in lookup.get('releases', [])}
    def tier1(order):
        for rid in order:
            release = by_id.get(rid, {})
            group = release.get('release-group') or {}
            if release.get('status') == 'Official' and group.get('primary-type') == 'Album':
                return group.get('id')
        return None
    search_pick, lookup_pick = tier1(search_order), tier1(lookup_order)
    print('  findArtReleaseGroup tier-1 pick, search order:', search_pick)
    print('  findArtReleaseGroup tier-1 pick, lookup order:', lookup_pick)
    print('  same release-group (so: same cover art) either way:', search_pick == lookup_pick)
"

cat <<EOF

Measured $(date -u '+%Y-%m-%d %H:%MZ') from $(hostname -s 2>/dev/null || echo 'an unnamed host'), TITLE="$TITLE" ARTIST="$ARTIST" LIMIT=$LIMIT.
A single run is a sample, not a rate -- re-run before citing a number from this script.
EOF
