#!/usr/bin/env python3
"""Final verdict for every Last.fm similar row carrying an MBID.

Three captures feed this, in order of how decisive they are:

  artists/<mbid>.json   MusicBrainz's own answer for the id Last.fm supplied, with url-rels.
  names/<n>.json        every MusicBrainz artist carrying the row's name.
  artists/<cand>.json   the same url-rels lookup for each of those same-name candidates.

Verdicts:
  GONE          MusicBrainz holds no artist under the supplied id.
  MERGED        the lookup answers with a different id: the supplied id was merged away.
  MATCH         the supplied id's own url relations name the last.fm page the row links to.
  MISMATCH      a *different* same-name MusicBrainz artist owns that page. Decisive: the id names
                one act and the row's own link names another.
  UNCONTESTED   the supplied id owns no last.fm page, and MusicBrainz holds no other artist under
                that name -- so nothing else could be the act the page belongs to.
  UNADJUDICATED the name is contested but no candidate, the supplied id included, carries the page.
                MusicBrainz is silent, and silence is not a verdict.

The last two are the false-positive population this probe must not count as defects.
"""

import json
import os
import collections
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(HERE, "contested.json")))


def norm_lastfm_url(u):
    if not u:
        return None
    p = urllib.parse.urlsplit(u)
    if "last.fm" not in p.netloc.lower():
        return None
    marker = "/music/"
    i = p.path.find(marker)
    if i < 0:
        return None
    return urllib.parse.unquote_plus(p.path[i + len(marker) :].split("/")[0]).strip().lower()


def artist(mbid):
    f = os.path.join(HERE, "artists", mbid.lower() + ".json")
    if not os.path.exists(f):
        return None
    try:
        return json.load(open(f))
    except Exception:
        return None


def lastfm_pages(body):
    if not body:
        return []
    out = []
    for rel in body.get("relations") or []:
        u = (rel.get("url") or {}).get("resource")
        n = norm_lastfm_url(u)
        if n:
            out.append(n)
    return out


out = []
for r in rows:
    rec = dict(r)
    v = r["verdict"]
    if v in ("GONE", "MERGED", "MATCH"):
        rec["final"] = v
        out.append(rec)
        continue
    if r.get("contest") == "UNCONTESTED":
        rec["final"] = "UNCONTESTED"
        out.append(rec)
        continue
    want = r["lastfm_page"]
    owners = []
    for cand in r.get("candidates", []):
        if cand["id"].lower() == r["mbid"].lower():
            continue
        if want in lastfm_pages(artist(cand["id"])):
            owners.append(cand)
    if owners:
        rec["final"] = "MISMATCH"
        rec["true_owner"] = owners[0]
        rec["all_owners"] = owners
    else:
        rec["final"] = "UNADJUDICATED"
    out.append(rec)

json.dump(out, open(os.path.join(HERE, "adjudicated.json"), "w"), indent=1)
c = collections.Counter(r["final"] for r in out)
print("rows carrying an MBID:", len(out))
for k, v in c.most_common():
    print("  %-14s %4d  %5.1f%%" % (k, v, 100.0 * v / len(out)))
decided = c["MATCH"] + c["MISMATCH"]
if decided:
    print(
        "\nof the %d rows MusicBrainz decides: %d mismatched (%.1f%%)"
        % (decided, c["MISMATCH"], 100.0 * c["MISMATCH"] / decided)
    )
print("\n=== MISMATCH ===")
for r in out:
    if r["final"] == "MISMATCH":
        t = r["true_owner"]
        print("%-17s %-22s supplied %s %r" % (r["workload"], r["name"], r["mbid"][:8], r.get("disambiguation")))
        print("%-17s %-22s   owner  %s %r" % ("", "", t["id"][:8], t["disambiguation"]))
print("\n=== UNADJUDICATED (contested, nobody carries the page) ===")
for r in out:
    if r["final"] == "UNADJUDICATED":
        print(
            "%-17s %-22s %s %r  (%d same-name artists)"
            % (r["workload"], r["name"], r["mbid"][:8], r.get("disambiguation"), len(r.get("candidates", [])))
        )
