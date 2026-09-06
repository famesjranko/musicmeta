#!/usr/bin/env bash
# One-off capture: MusicBrainz artist lookup for the eight split-pair MBIDs.
set -euo pipefail
OUT="$1"
mkdir -p "$OUT"
UA="musicmeta-probe-09-similar-artist-disambiguation/0.1 ( andrewmcdonald42@gmail.com )"
for MBID in \
  eecada09-acfc-472d-ae55-e9e5a43f12d8 \
  8834d8b5-72a4-4a6e-9d35-3a041b8579fa \
  9c935736-7530-41e4-b776-1dbcf534c061 \
  a39ad456-a697-4f32-aa36-c107f654d318 \
  56eb02c4-1f16-4613-8bb3-b4a752283fc3 \
  e9ea0fbc-ccc7-4e98-9290-0a41aa848fa2 \
  beb404c4-9d0b-4042-a56c-aad7673c677d \
  21006fdb-2e22-4950-91ed-8575a1a96b58 ; do
  curl -s -m 40 -A "$UA" "https://musicbrainz.org/ws/2/artist/${MBID}?fmt=json" -o "$OUT/${MBID}.json"
  echo "$MBID $(python3 -c 'import json,sys;j=json.load(open(sys.argv[1]));print(repr(j.get("name")),repr(j.get("disambiguation")),j.get("type"),j.get("country"),(j.get("life-span") or {}).get("begin"))' "$OUT/${MBID}.json")"
  sleep 1.4
done
