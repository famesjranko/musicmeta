#!/usr/bin/env python3
"""Fold the requested artists' own MusicBrainz records into a workload's table.

These come from the lookup the engine already makes on an artist enrich, so arm C reads them for
free. `changg` has no Labs answer and therefore no `reference_mbid` to read the id off, so it has no
pool and arm C makes no request for it -- recorded here rather than papered over.
"""

import glob
import json
import os
import sys

CAP, OUT = sys.argv[1], sys.argv[2]
table = json.load(open(OUT))
requested = {}
for f in sorted(glob.glob(os.path.join(CAP, "requested", "*.json"))):
    slug = os.path.basename(f)[:-5]
    b = json.load(open(f))
    if "id" not in b:
        continue
    requested[slug] = {
        "id": b["id"],
        "name": b.get("name"),
        "tags": sorted({t["name"].lower() for t in (b.get("tags") or []) if (t.get("count") or 0) > 0}),
        "genres": sorted({g["name"].lower() for g in (b.get("genres") or [])}),
    }
table["requested"] = requested
json.dump(table, open(OUT, "w"), indent=1, sort_keys=True)
print(
    OUT,
    len(requested),
    "requested artists;",
    [(s, len(v["tags"]) + len(v["genres"])) for s, v in sorted(requested.items())],
)
