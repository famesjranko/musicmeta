#!/usr/bin/env python3
"""Turn one workload's raw MusicBrainz captures into the offline table the arms consult.

The arms make no live call. Every consultation they make is served from this file and *billed* as
the request it stands in for, so metric 3 is counted rather than estimated.

  artists[mbid]           name, disambiguation, type, the normalised last.fm pages MusicBrainz says
                          that artist owns, and its vote-carrying tags and curated genres
  nameCandidates[name]    every MusicBrainz artist id whose name or an alias equals that name,
                          from one `artist:"name"` search -- present only for the names a search was
                          run for, which is exactly the rows whose own id did not carry their page
  groundTruth[key]        the adjudication inventory.md defines, keyed workload|index

Usage: table.py <capture-dir> <rows.json> <out.json>
"""

import json
import os
import sys
import urllib.parse

CAP, ROWS, OUT = sys.argv[1], sys.argv[2], sys.argv[3]


def norm_page(u):
    if not u:
        return None
    p = urllib.parse.urlsplit(u)
    if "last.fm" not in p.netloc.lower():
        return None
    i = p.path.find("/music/")
    if i < 0:
        return None
    return urllib.parse.unquote_plus(p.path[i + 7 :].split("/")[0]).strip().lower()


def artist_record(mbid):
    f = os.path.join(CAP, "artists", mbid.lower() + ".json")
    if not os.path.exists(f):
        return None
    try:
        b = json.load(open(f))
    except Exception:
        return None
    if "id" not in b:
        return None
    pages = sorted({norm_page((r.get("url") or {}).get("resource")) for r in (b.get("relations") or [])} - {None})
    return {
        "id": b["id"],
        "name": b.get("name"),
        "disambiguation": b.get("disambiguation") or "",
        "type": b.get("type"),
        "lastfmPages": pages,
        "tags": sorted({t["name"].lower() for t in (b.get("tags") or []) if (t.get("count") or 0) > 0}),
        "genres": sorted({g["name"].lower() for g in (b.get("genres") or [])}),
    }


rows = json.load(open(ROWS))
artists = {}
wanted = {r["mbid"].lower() for r in rows}

name_candidates = {}
idx_path = os.path.join(CAP, "names", "index.tsv")
if os.path.exists(idx_path):
    for line in open(idx_path):
        slug, name = line.rstrip("\n").split("\t", 1)
        f = os.path.join(CAP, "names", slug + ".json")
        if not os.path.exists(f):
            continue
        body = json.load(open(f))
        key = name.strip().lower()
        ids = []
        for a in body.get("artists", []):
            names = {(a.get("name") or "").strip().lower()}
            for al in a.get("aliases") or []:
                names.add((al.get("name") or "").strip().lower())
            if key in names and (a.get("name") or "").strip().lower() == key:
                ids.append(a["id"])
        name_candidates[key] = ids
        wanted |= {i.lower() for i in ids}

for m in sorted(wanted):
    rec = artist_record(m)
    if rec:
        artists[m] = rec

adj_path = os.path.join(CAP, "adjudicated.json")
ground = {}
if os.path.exists(adj_path):
    adj = json.load(open(adj_path))
    counters = {}
    for r in adj:
        w = r["workload"]
        i = counters.get(w, 0)
        counters[w] = i + 1
        ground["%s|%d" % (w, i)] = {
            "mbid": r["mbid"].lower(),
            "verdict": r["final"],
            "owner": (r.get("true_owner") or {}).get("id", ""),
        }

captured_on = "2026-09-06"
json.dump(
    {"capturedOn": captured_on, "artists": artists, "nameCandidates": name_candidates, "groundTruth": ground},
    open(OUT, "w"),
    indent=1,
    sort_keys=True,
)
print(OUT, len(artists), "artists,", len(name_candidates), "name searches,", len(ground), "ground-truth rows")
