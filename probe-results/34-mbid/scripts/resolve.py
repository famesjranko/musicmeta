#!/usr/bin/env python3
"""Resolve each held-out artist to the MusicBrainz id Labs needs and the Deezer id its related
route needs, the same way the library's own providers do: MusicBrainz `artist:"name"` search and
Deezer `/search/artist`. Top hit only; the pair is printed for a human to eyeball before capture."""

import json
import sys
import time
import urllib.parse
import urllib.request

UA = "musicmeta-probe-34-lastfm-similar-mbid/0.1 ( andrewmcdonald42@gmail.com )"

NAMES = [
    ("blackpink", "BLACKPINK"),
    ("claude-debussy", "Claude Debussy"),
    ("a-tribe-called-quest", "A Tribe Called Quest"),
    ("the-bad-plus", "The Bad Plus"),
    ("trouble", "Trouble"),
    ("kino", "Кино"),
    ("alison-krauss", "Alison Krauss"),
    ("four-tet", "Four Tet"),
    ("shakira", "Shakira"),
    ("the-zombies", "The Zombies"),
    ("alvvays", "Alvvays"),
    ("nico", "Nico"),
]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except Exception as e:
            time.sleep(2 + 3 * attempt)
            last = e
    raise last


rows = []
for slug, name in NAMES:
    q = urllib.parse.quote('artist:"%s" OR alias:"%s"' % (name, name), safe="")
    time.sleep(1.4)
    mb = get("https://musicbrainz.org/ws/2/artist?query=%s&fmt=json&limit=5" % q)
    top = (mb.get("artists") or [None])[0]
    time.sleep(0.6)
    dz = get("https://api.deezer.com/search/artist?q=%s&limit=5" % urllib.parse.quote(name, safe=""))
    dtop = (dz.get("data") or [None])[0]
    rows.append((slug, name, top["id"] if top else "", dtop["id"] if dtop else ""))
    print(
        "%-22s %-22s %-38s %s | mb=%r %r | dz=%r"
        % (
            slug,
            name,
            top["id"] if top else "-",
            dtop["id"] if dtop else "-",
            top.get("name") if top else None,
            top.get("disambiguation") if top else None,
            dtop.get("name") if dtop else None,
        ),
        flush=True,
    )

with open(sys.argv[1], "w") as f:
    for r in rows:
        f.write("%s\t%s\t%s\t%s\n" % r)
