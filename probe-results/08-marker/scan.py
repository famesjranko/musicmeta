"""Incidence of Discogs' trailing `*` name-variation marker in the strings our credit test reads.

Population, exactly as plan.md froze it: for every `/database/search?type=release` hit in
captures/, every artist-side prefix `parseDiscogsRelease` tries — one per ` - ` boundary of the
hit's `title`, with Discogs' ` (n)` disambiguator stripped — plus each `", "` part of it.

Reports the raw string incidence only. Whether a marked string actually fails the shipped name test
is metric B, computed by the Kotlin probe, not here.
"""

import json
import pathlib
import re
import sys

DISAMBIGUATOR = re.compile(r"\s\(\d+\)")
HERE = pathlib.Path(__file__).parent
EXTRA = [pathlib.Path("/home/andy/dev/musicmeta/musicmeta-core/src/test/resources/pools")]


def strings(title):
    """Every artist-side string the credit test sees for one hit title."""
    out = []
    from_ = 0
    while True:
        i = title.find(" - ", from_)
        if i < 0:
            return out
        artist = DISAMBIGUATOR.sub("", title[:i]).strip()
        if artist:
            out.append(artist)
            parts = [p.strip() for p in artist.split(", ") if p.strip()]
            if len(parts) > 1:
                out.extend(parts)
        from_ = i + 3


def bodies():
    for f in sorted(HERE.joinpath("captures").glob("*.json")):
        yield f.name, json.loads(f.read_text())
    for root in EXTRA:
        for f in sorted(root.rglob("*.json")):
            try:
                doc = json.loads(f.read_text())
            except ValueError:
                continue
            if isinstance(doc, dict) and "results" in doc:
                yield str(f), doc


def main():
    total = 0
    trailing = []
    interior = []
    hits = 0
    for name, doc in bodies():
        for hit in doc.get("results", []):
            title = hit.get("title") or ""
            hits += 1
            for s in strings(title):
                total += 1
                if s.endswith("*"):
                    trailing.append((name, hit.get("id"), s))
                if "*" in s.rstrip("*"):
                    interior.append((name, hit.get("id"), s))
    print(f"hits={hits} population={total}")
    print(f"trailing_marker={len(trailing)} ({100.0 * len(trailing) / max(total, 1):.2f}%)")
    print(f"interior_asterisk={len(interior)}")
    for row in trailing:
        print("TRAILING", *row, sep="\t")
    for row in interior:
        print("INTERIOR", *row, sep="\t")
    return 0


if __name__ == "__main__":
    sys.exit(main())
