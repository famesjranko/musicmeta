#!/usr/bin/env python3
"""Pin the CHANGELOG `[Unreleased]` section to a version, move the ROADMAP heading, and rename the
migration guide's `## Unreleased` section.

All three edits are single-line (or single-heading) and fully determined by the version and date,
so a human doing them by hand is three chances to typo the thing every later check reads.
`prepare-release.yml` runs this.

    python3 pin_release.py 0.11.0 [--date YYYY-MM-DD] [--changelog PATH] [--roadmap PATH]
                                  [--migration-guide PATH]

Exit codes: 0 = pinned, 1 = refused (the message says why).
Importable: pin_changelog(text, version, date) -> str, pin_roadmap(text, version) -> str,
pin_migration_guide(text, version) -> str.
"""

from __future__ import annotations

import argparse
import datetime
import re
import sys
from pathlib import Path

UNRELEASED = re.compile(r"^## \[Unreleased\][ \t]*$", re.MULTILINE)
PINNED = re.compile(r"^## \[([0-9]+\.[0-9]+\.[0-9]+)\]", re.MULTILINE)
BREAKING = re.compile(r"^### Breaking Changes[ \t]*$", re.MULTILINE)
ROADMAP_HEADING = re.compile(r"^## Where We Are \(v[0-9]+\.[0-9]+\.[0-9]+\)[ \t]*$", re.MULTILINE)
MIGRATION_GUIDE_HEADING = re.compile(r"^## Unreleased[ \t]*$", re.MULTILINE)

# The regions the ROADMAP names the version in, and the coordinate shape, are imported from the
# check that reads them rather than restated here. Two copies of this rule is the defect one
# directory over: the pin moved the heading, the check read nothing, and the prose between them
# went stale.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "checks"))

from check_release_coordinates import (  # noqa: E402
    ANY_VERSION,
    COORDINATE_FILES,
    REGIONS,
    region_lines,
)

COORDINATE_SUB = re.compile(r"(musicmeta-[a-z]+):(v?)[0-9]+\.[0-9]+\.[0-9]+")

MIGRATION_GUIDE = "docs/guides/migration.md"


class PinError(Exception):
    """Raised when there is nothing to pin, or nothing worth pinning."""


def _same_minor(a: str, b: str) -> bool:
    """True when two versions differ only in the patch component, i.e. one is a patch of the other."""
    return a.split(".")[:2] == b.split(".")[:2]


def pin_changelog(text: str, version: str, date: str) -> str:
    """Rename `## [Unreleased]` to `## [version] - date` and open a fresh empty `[Unreleased]`."""
    match = UNRELEASED.search(text)
    if not match:
        raise PinError("CHANGELOG has no '## [Unreleased]' heading to pin")

    # Before the emptiness check, or a re-run reports the freshly-opened empty [Unreleased] rather
    # than the real problem, which is that this version is already pinned.
    if any(v == version for v in PINNED.findall(text)):
        raise PinError(f"CHANGELOG already has a '## [{version}]' section")

    # Everything between [Unreleased] and the next `## ` heading is what would ship.
    rest = text[match.end() :]
    next_heading = rest.find("\n## ")
    body = rest if next_heading == -1 else rest[:next_heading]
    if not body.strip():
        raise PinError("the [Unreleased] section is empty — there is nothing to release")

    # A break may ship in a minor (0.x.0), never in a patch (CLAUDE.md, Compatibility). v0.9.2 broke
    # the profile extensions in a patch and there was nothing to stop it; this is that stop.
    previous = PINNED.findall(text)
    if BREAKING.search(body) and previous and _same_minor(previous[0], version):
        raise PinError(
            f"[Unreleased] has a '### Breaking Changes' section, so {version} cannot be a patch "
            f"release over {previous[0]} — a break ships in a minor. Bump the minor, or move the "
            "entry out of Breaking Changes if it does not belong there."
        )

    return text[: match.start()] + f"## [Unreleased]\n\n## [{version}] - {date}" + text[match.end() :]


def pin_roadmap(text: str, version: str) -> str:
    """Point the 'Where We Are' block at this version. Absent heading is not an error.

    The heading and the prose under it both name the version, and until 0.12.0 only the heading
    moved — the sentences below it went a full release stale, unnoticed for three weeks.

    "vX is published to Maven Central" is true of the *previous* version until gate 3 finishes and
    of the new one after, so which to write is a real choice: this writes the version being
    prepared, correct for the whole life of the doc except the minutes between gate 2 and gate 3.
    `check_release_coordinates.py` reads the same regions and enforces the same choice.
    """
    text = ROADMAP_HEADING.sub(f"## Where We Are (v{version})", text, count=1)
    for heading in REGIONS:
        lines = region_lines(text, heading)
        if lines is None:
            continue
        body = text.splitlines(keepends=True)
        for number, _ in lines:
            line = body[number - 1]
            body[number - 1] = ANY_VERSION.sub(lambda m: f"{'v' if m.group(0).startswith('v') else ''}{version}", line)
        text = "".join(body)
    return text


def pin_guides(text: str, version: str) -> str:
    """Rewrite every musicmeta dependency coordinate in a guide or README to this version.

    Gate 1 already did this for README with two `sed` lines; the three guides were a by-hand step in
    `docs/project/release.md`. Same rewrite, same rule, one place.
    """
    return COORDINATE_SUB.sub(lambda m: f"{m.group(1)}:{m.group(2)}{version}", text)


def pin_migration_guide(text: str, version: str) -> str:
    """Rename the guide's `## Unreleased` heading to `## <version>`. Absent heading is not an error.

    The guide heads its newest group `## Unreleased` to match CHANGELOG.md's own section; nothing
    renamed it at release time, so a consumer on the released version read "Unreleased" above
    breaks they already had. `check_migration_guide.py` reads the same heading and enforces the
    same rename on every later commit.
    """
    return MIGRATION_GUIDE_HEADING.sub(f"## {version}", text, count=1)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Pin the CHANGELOG and ROADMAP to a release version.")
    parser.add_argument("version", help="release version, e.g. 0.11.0 (leading v allowed)")
    parser.add_argument("--date", help="release date, YYYY-MM-DD (default: today, UTC)")
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--roadmap", default="ROADMAP.md")
    parser.add_argument("--migration-guide", default=MIGRATION_GUIDE)
    args = parser.parse_args(argv)

    version = args.version.removeprefix("v")
    date = args.date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")

    changelog = Path(args.changelog)
    try:
        pinned = pin_changelog(changelog.read_text(encoding="utf-8"), version, date)
    except PinError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1

    roadmap = Path(args.roadmap)
    roadmap_text = roadmap.read_text(encoding="utf-8") if roadmap.exists() else ""
    roadmap_pinned = pin_roadmap(roadmap_text, version)

    changelog.write_text(pinned, encoding="utf-8")
    if roadmap_text and roadmap_pinned != roadmap_text:
        roadmap.write_text(roadmap_pinned, encoding="utf-8")
        print(f"Pinned [Unreleased] -> [{version}] - {date}, and moved the ROADMAP block.")
    else:
        print(f"Pinned [Unreleased] -> [{version}] - {date}. ROADMAP block unchanged.")

    root = Path(args.roadmap).resolve().parent
    for rel in COORDINATE_FILES:
        path = root / rel
        if not path.exists():
            continue
        before = path.read_text(encoding="utf-8")
        after = pin_guides(before, version)
        if after != before:
            path.write_text(after, encoding="utf-8")
            print(f"Rewrote coordinates in {rel}.")

    migration_guide = Path(args.migration_guide)
    if migration_guide.exists():
        before = migration_guide.read_text(encoding="utf-8")
        after = pin_migration_guide(before, version)
        if after != before:
            migration_guide.write_text(after, encoding="utf-8")
            print(f"Renamed {migration_guide}'s Unreleased heading to {version}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
