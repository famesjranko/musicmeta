#!/usr/bin/env python3
"""Capture real release-group / release structure, for the two album-contradiction arms.

Population: the same 99 chart artists captured for #265's name corpus
(`.scratch/artist-mbid-provenance/names_correct.json`), so the artist list keeps its provenance -
Last.fm's chart, with each MBID resolved from MusicBrainz's own search, not hand-written.

For each artist this takes up to ALBUMS_PER_ARTIST studio release groups and every release in them,
with each release's audio track count computed the way `MusicBrainzParser.parseMedia` computes it:
video media dropped by exact format name, tracks summed across the remaining media.

Re-run before trusting any number: this is a live capture with a date.
"""
import json, time, urllib.request, urllib.error, pathlib, sys

UA = "musicmeta-research/0.1 (https://github.com/andrewmcdonald42/musicmeta)"
BASE = "https://musicbrainz.org/ws/2"
HERE = pathlib.Path(__file__).parent
ARTISTS = HERE.parent / "artist-mbid-provenance" / "names_correct.json"
ALBUMS_PER_ARTIST = 2

# Mirrors MusicBrainzParser.VIDEO_MEDIA_FORMATS exactly. A corpus that counted video tracks the
# production code drops would be measuring a rule nobody ships.
VIDEO_FORMATS = {
    "dvd", "dvd-video", "blu-ray", "hd-dvd", "vhs", "vcd", "svcd", "betamax", "umd",
    "laserdisc", '8" laserdisc', '12" laserdisc',
    "dualdisc (dvd-video side)", "dvdplus (dvd-video side)",
}


def get(url):
    """Retries throttling AND transport failures - see capture_names.py, which lost 90 requests
    to a socket timeout an HTTP-only retry policy did not catch."""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (503, 429):
                time.sleep(2 * (attempt + 1)); continue
            return e.code, None
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            print(f"  .. transport failure, retrying: {e}", flush=True)
            time.sleep(2 * (attempt + 1)); continue
    return 0, None


def audio_track_count(media):
    """Total audio tracks across every non-video medium, as parseMedia counts them."""
    total = 0
    audio = [m for m in media if (m.get("format") or "").strip().lower() not in VIDEO_FORMATS]
    for m in (audio or media):
        total += m.get("track-count") or 0
    return total


def year_of(date):
    return int(date[:4]) if date and len(date) >= 4 and date[:4].isdigit() else None


def studio_groups(artist_mbid):
    """Studio albums only: a compilation or live album's track count and year are a different
    question, and mixing them in would measure the rule against a population it never sees."""
    status, body = get(f"{BASE}/release-group?artist={artist_mbid}&type=album&limit=50&fmt=json")
    time.sleep(1.1)
    if status != 200 or not body:
        return []
    out = []
    for g in body.get("release-groups", []):
        if g.get("primary-type") != "Album" or g.get("secondary-types"):
            continue
        if not year_of(g.get("first-release-date")):
            continue
        out.append({
            "rg_id": g["id"], "rg_title": g["title"],
            "rg_first_year": year_of(g.get("first-release-date")),
        })
    return out


def releases_in(rg):
    status, body = get(f"{BASE}/release?release-group={rg['rg_id']}&inc=media&limit=100&fmt=json")
    time.sleep(1.1)
    if status != 200 or not body:
        return []
    out = []
    for r in body.get("releases", []):
        tracks = audio_track_count(r.get("media") or [])
        if tracks <= 0:
            continue
        out.append({
            "id": r["id"], "title": r["title"], "date": r.get("date"),
            "year": year_of(r.get("date")), "tracks": tracks,
            "status": r.get("status"), "country": r.get("country"),
        })
    return out


def main():
    out = HERE / "albums.json"
    done = {}
    if out.exists():
        done = {row["artist_mbid"]: row for row in json.load(open(out))}
    artists = json.load(open(ARTISTS))
    rows = []
    for a in artists:
        mbid, name = a["mbid"], a.get("name") or a.get("supplied")
        if mbid in done:
            rows.append(done[mbid]); continue
        groups = studio_groups(mbid)[:ALBUMS_PER_ARTIST]
        for g in groups:
            g["releases"] = releases_in(g)
        row = {"artist": name, "artist_mbid": mbid, "groups": groups}
        rows.append(row)
        n = sum(len(g["releases"]) for g in groups)
        print(f"  {name}: {len(groups)} groups, {n} releases", flush=True)
        json.dump(rows, open(out, "w"), indent=1, ensure_ascii=False)
    json.dump(rows, open(out, "w"), indent=1, ensure_ascii=False)
    groups = sum(len(r["groups"]) for r in rows)
    releases = sum(len(g["releases"]) for r in rows for g in r["groups"])
    print(f"wrote albums.json: {len(rows)} artists, {groups} groups, {releases} releases")


if __name__ == "__main__":
    sys.exit(main())
