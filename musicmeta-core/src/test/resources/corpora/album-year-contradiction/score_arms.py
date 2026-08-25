#!/usr/bin/env python3
"""Score RULE-Y and RULE-T against albums.json. Rules are as frozen in spec.md - do not tune here.

Reports, for each arm, firings on four populations:

  identical      caller metadata is the release its own MBID names. A firing cannot be right.
  same_group     caller's MBID is release R, caller's tags came from R' in the SAME release group -
                 a different pressing of the same album. A firing is a false accusation.
  drift          MusicBrainz contradicting itself: a release dated earlier than its own group's
                 first-release-date. This is the ONLY population that can falsify RULE-Y, because
                 rg_first_year is the minimum over the group, so same_group cannot by construction.
  cross_group    R's MBID against a different album BY THE SAME ARTIST. The case the ticket exists
                 to catch, and the case contradictsSuppliedName provably cannot see.
"""
import json, pathlib, random

HERE = pathlib.Path(__file__).parent
PAIRS_PER_GROUP = 20
SEED = 20260825


def rule_y(caller_year, caller_tracks, rg_first_year, release_tracks):
    if caller_year is None or rg_first_year is None:
        return False
    return caller_year < rg_first_year - 1


def rule_t(caller_year, caller_tracks, rg_first_year, release_tracks):
    if caller_tracks is None or not release_tracks or release_tracks <= 0:
        return False
    return abs(caller_tracks - release_tracks) > max(2, release_tracks // 5)


ARMS = {"RULE-Y": rule_y, "RULE-T": rule_t}


def build(rows):
    rnd = random.Random(SEED)
    identical, same_group, cross_group, drift = [], [], [], []
    for row in rows:
        groups = [g for g in row["groups"] if g["releases"]]
        for g in groups:
            for r in g["releases"]:
                identical.append((r["year"], r["tracks"], g["rg_first_year"], r["tracks"], row["artist"], g["rg_title"]))
                if r["year"] is not None and r["year"] < g["rg_first_year"] - 1:
                    drift.append((row["artist"], g["rg_title"], g["rg_first_year"], r["year"], r["title"]))
            pairs = [(a, b) for a in g["releases"] for b in g["releases"] if a["id"] != b["id"]]
            rnd.shuffle(pairs)
            for supplied, tagged in pairs[:PAIRS_PER_GROUP]:
                same_group.append((tagged["year"], tagged["tracks"], g["rg_first_year"], supplied["tracks"],
                                   row["artist"], g["rg_title"]))
        for i, g in enumerate(groups):
            for j, other in enumerate(groups):
                if i == j:
                    continue
                supplied = g["releases"][0]
                tagged = other["releases"][0]
                cross_group.append((tagged["year"], tagged["tracks"], g["rg_first_year"], supplied["tracks"],
                                    row["artist"], f"{g['rg_title']} <- tags of {other['rg_title']}"))
    return identical, same_group, cross_group, drift


def main():
    rows = json.load(open(HERE / "albums.json"))
    identical, same_group, cross_group, drift = build(rows)
    print(f"artists={len(rows)} identical={len(identical)} same_group={len(same_group)} "
          f"cross_group={len(cross_group)} drift={len(drift)}\n")

    for name, rule in ARMS.items():
        fp_i = [p for p in identical if rule(*p[:4])]
        fp_s = [p for p in same_group if rule(*p[:4])]
        catch = [p for p in cross_group if rule(*p[:4])]
        fp = len(fp_i) + len(fp_s)
        print(f"{name}")
        print(f"  false positives : {fp}  (identical {len(fp_i)}/{len(identical)}, "
              f"same_group {len(fp_s)}/{len(same_group)})")
        print(f"  caught          : {len(catch)}/{len(cross_group)} "
              f"({100.0 * len(catch) / max(1, len(cross_group)):.0f}%)")
        for p in (fp_i + fp_s)[:8]:
            print(f"    FP: {p[4]} / {p[5]}: caller year={p[0]} tracks={p[1]} vs "
                  f"rg_first={p[2]} release_tracks={p[3]}")
        print(f"  SHIP: {'yes' if fp == 0 else 'NO - fires on a correct pair'}\n")

    print(f"drift (release dated before its own group's first-release-date - 1): {len(drift)}")
    for d in drift[:10]:
        print(f"  {d[0]} / {d[1]}: rg_first={d[2]} but release {d[4]!r} dated {d[3]}")


if __name__ == "__main__":
    main()
