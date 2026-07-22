#!/usr/bin/env python3
"""Pin the CHANGELOG `[Unreleased]` section to a version, and move the ROADMAP heading.

Both edits are single-line and fully determined by the version and date, so a human doing them by
hand is three chances to typo the thing every later check reads. `prepare-release.yml` runs this.

    python3 pin_release.py 0.11.0 [--date YYYY-MM-DD] [--changelog PATH] [--roadmap PATH] [--check]

Exit codes: 0 = pinned (or already correct), 1 = nothing to pin, 2 = the section is empty.
Importable: pin_changelog(text, version, date) -> str, pin_roadmap(text, version) -> str.
"""
from __future__ import annotations

import argparse
import datetime
import re
import sys
from pathlib import Path

UNRELEASED = re.compile(r"^## \[Unreleased\][ \t]*$", re.MULTILINE)
PINNED = re.compile(r"^## \[([0-9]+\.[0-9]+\.[0-9]+)\]", re.MULTILINE)
ROADMAP_HEADING = re.compile(r"^## Where We Are \(v[0-9]+\.[0-9]+\.[0-9]+\)[ \t]*$", re.MULTILINE)


class PinError(Exception):
    """Raised when there is nothing to pin, or nothing worth pinning."""


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
    rest = text[match.end():]
    next_heading = rest.find("\n## ")
    body = rest if next_heading == -1 else rest[:next_heading]
    if not body.strip():
        raise PinError("the [Unreleased] section is empty — there is nothing to release")

    return text[:match.start()] + f"## [Unreleased]\n\n## [{version}] - {date}" + text[match.end():]


def pin_roadmap(text: str, version: str) -> str:
    """Point the 'Where We Are' heading at this version. Absent heading is not an error."""
    return ROADMAP_HEADING.sub(f"## Where We Are (v{version})", text, count=1)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Pin the CHANGELOG and ROADMAP to a release version.")
    parser.add_argument("version", help="release version, e.g. 0.11.0 (leading v allowed)")
    parser.add_argument("--date", help="release date, YYYY-MM-DD (default: today, UTC)")
    parser.add_argument("--changelog", default="CHANGELOG.md")
    parser.add_argument("--roadmap", default="ROADMAP.md")
    parser.add_argument("--check", action="store_true", help="report what would change, write nothing")
    args = parser.parse_args(argv)

    version = args.version.removeprefix("v")
    date = args.date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")

    changelog = Path(args.changelog)
    try:
        pinned = pin_changelog(changelog.read_text(encoding="utf-8"), version, date)
    except PinError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 2 if "empty" in str(e) else 1

    roadmap = Path(args.roadmap)
    roadmap_text = roadmap.read_text(encoding="utf-8") if roadmap.exists() else ""
    roadmap_pinned = pin_roadmap(roadmap_text, version)

    if args.check:
        print(f"Would pin [Unreleased] -> [{version}] - {date}")
        return 0

    changelog.write_text(pinned, encoding="utf-8")
    if roadmap_text and roadmap_pinned != roadmap_text:
        roadmap.write_text(roadmap_pinned, encoding="utf-8")
        print(f"Pinned [Unreleased] -> [{version}] - {date}, and moved the ROADMAP heading.")
    else:
        print(f"Pinned [Unreleased] -> [{version}] - {date}. ROADMAP heading unchanged.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
