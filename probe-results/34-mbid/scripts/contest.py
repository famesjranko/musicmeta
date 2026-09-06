#!/usr/bin/env python3
"""Reduce the UNKNOWN rows: is the id even contested?

classify.py leaves a row UNKNOWN when the MBID it carries has no last.fm relation. That is
MusicBrainz's silence, not a verdict. This asks the reducing question instead: how many artists does
MusicBrainz hold under the row's own name? If exactly one, and it is the supplied id, then no other
act can be the one the row's last.fm page belongs to, and the row is UNCONTESTED. If more than one,
the row is CONTESTED and needs the per-candidate url-rels lookup.
"""

import json
import os
import collections

HERE = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(HERE, "classified.json")))
index = {}
for line in open(os.path.join(HERE, "names", "index.tsv")):
    slug, name = line.rstrip("\n").split("\t", 1)
    index[name.strip().lower()] = slug


def exact_candidates(name):
    slug = index.get(name.strip().lower())
    if not slug:
        return None
    f = os.path.join(HERE, "names", slug + ".json")
    if not os.path.exists(f):
        return None
    body = json.load(open(f))
    key = name.strip().lower()
    out = []
    for a in body.get("artists", []):
        names = {(a.get("name") or "").strip().lower()}
        for al in a.get("aliases") or []:
            names.add((al.get("name") or "").strip().lower())
        if key in names:
            out.append(
                {
                    "id": a["id"],
                    "name": a.get("name"),
                    "disambiguation": a.get("disambiguation", ""),
                    "type": a.get("type"),
                    "score": a.get("score"),
                    "exact_name": (a.get("name") or "").strip().lower() == key,
                }
            )
    return out


out = []
for r in rows:
    rec = dict(r)
    if r["verdict"] in ("UNKNOWN", "MISMATCH"):
        cands = exact_candidates(r["name"])
        if cands is None:
            rec["contest"] = "NO-SEARCH"
        else:
            same_name = [c for c in cands if c["exact_name"]]
            rec["candidates"] = same_name
            ids = {c["id"].lower() for c in same_name}
            if len(same_name) <= 1:
                rec["contest"] = "UNCONTESTED"
            elif r["mbid"].lower() not in ids:
                rec["contest"] = "CONTESTED-ID-NOT-IN-SEARCH"
            else:
                rec["contest"] = "CONTESTED"
    out.append(rec)

json.dump(out, open(os.path.join(HERE, "contested.json"), "w"), indent=1)
c = collections.Counter(r.get("contest") for r in out if r.get("contest"))
print("UNKNOWN/MISMATCH rows:", sum(c.values()))
for k, v in c.most_common():
    print("  %-28s %3d" % (k, v))
print()
for r in out:
    if r.get("contest", "").startswith("CONTESTED"):
        print(r["contest"], "|", r["workload"], "|", r["name"], "|", r["mbid"], repr(r.get("disambiguation")))
        for cand in r.get("candidates", []):
            mark = "*" if cand["id"].lower() == r["mbid"].lower() else " "
            print("    %s %s %r %s" % (mark, cand["id"], cand["disambiguation"], cand["type"]))
