#!/usr/bin/env python3
"""Classify every Last.fm similar row that carries an MBID against MusicBrainz.

Population: the 198 rows (184 distinct ids) Last.fm's artist.getSimilar returned for the twelve
frozen workload artists. For each id the capture holds MusicBrainz's own answer with
inc=url-rels+tags+genres.

Verdicts, in the order they are tested:
  GONE       MusicBrainz answers 404 -- it holds no artist under this id at all.
  MERGED     MusicBrainz answers 200 but with a *different* id: the id was merged away and the
             lookup resolves to the survivor. Mechanically resolvable.
  MATCH      The id's own url relations name the very last.fm page the Last.fm row's `url` names.
  MISMATCH   The id carries last.fm relations, and none of them is that page -- the id names a
             different act from the one whose page Last.fm linked.
  UNKNOWN    The id carries no last.fm relation. MusicBrainz cannot adjudicate it from this
             capture; the reverse url lookup (reverse.sh) is what settles these.
"""

import json, os, urllib.parse, collections

HERE = os.path.dirname(os.path.abspath(__file__))
rows = json.load(open(os.path.join(HERE, "rows.json")))


def norm_lastfm_url(u):
    """A last.fm artist page as a comparable key: host-less, unescaped, lowercased path tail."""
    if not u:
        return None
    p = urllib.parse.urlsplit(u)
    if "last.fm" not in p.netloc.lower():
        return None
    path = p.path
    marker = "/music/"
    i = path.find(marker)
    if i < 0:
        return None
    tail = path[i + len(marker) :].split("/")[0]
    tail = urllib.parse.unquote_plus(tail)
    return tail.strip().lower()


def load(mbid):
    f = os.path.join(HERE, "artists", mbid + ".json")
    s = os.path.join(HERE, "artists", mbid + ".status")
    status = open(s).read().strip() if os.path.exists(s) else "?"
    body = None
    if os.path.exists(f):
        try:
            body = json.load(open(f))
        except Exception:
            body = None
    return status, body


out = []
for r in rows:
    mbid = r["mbid"].lower()
    status, body = load(mbid)
    rec = dict(r)
    rec["status"] = status
    want = norm_lastfm_url(r["url"])
    rec["lastfm_page"] = want
    if status == "404":
        rec["verdict"] = "GONE"
        out.append(rec)
        continue
    if body is None or "id" not in body:
        rec["verdict"] = "UNCAPTURED"
        out.append(rec)
        continue
    rec["mb_id"] = body["id"]
    rec["mb_name"] = body.get("name")
    rec["disambiguation"] = body.get("disambiguation") or ""
    rec["type"] = body.get("type")
    rec["genres"] = sorted({g["name"] for g in (body.get("genres") or [])})
    rec["tags"] = sorted({t["name"] for t in (body.get("tags") or []) if (t.get("count") or 0) > 0})
    rels = [rel["url"]["resource"] for rel in (body.get("relations") or []) if rel.get("url")]
    lf = [norm_lastfm_url(u) for u in rels]
    lf = [x for x in lf if x]
    rec["mb_lastfm_pages"] = sorted(set(lf))
    if body["id"].lower() != mbid:
        rec["verdict"] = "MERGED"
    elif not lf:
        rec["verdict"] = "UNKNOWN"
    elif want in lf:
        rec["verdict"] = "MATCH"
    else:
        rec["verdict"] = "MISMATCH"
    out.append(rec)

json.dump(out, open(os.path.join(HERE, "classified.json"), "w"), indent=1)
c = collections.Counter(r["verdict"] for r in out)
print("rows:", len(out))
for k, v in c.most_common():
    print("  %-10s %4d  %5.1f%%" % (k, v, 100.0 * v / len(out)))
print()
for r in out:
    if r["verdict"] in ("MISMATCH", "MERGED", "GONE"):
        print(
            r["verdict"],
            r["workload"],
            "|",
            r["name"],
            "|",
            r["mbid"],
            "->",
            r.get("mb_id"),
            "|",
            repr(r.get("mb_name")),
            repr(r.get("disambiguation")),
            "| wanted",
            r["lastfm_page"],
            "| mb has",
            r.get("mb_lastfm_pages"),
        )
