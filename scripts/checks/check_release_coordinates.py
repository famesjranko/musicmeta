#!/usr/bin/env python3
"""Fail on a doc that still names a version other than the one `gradle.properties` declares.

Three documents name the release version outside `CHANGELOG.md`: `ROADMAP.md`'s "Where We Are"
block, `README.md`'s dependency coordinates, and the same coordinates in three guides. Gate 1
rewrites the ROADMAP heading and README's coordinates; before this check, nothing rewrote or read
the rest. Preparing 0.12.0 the ROADMAP prose was found still naming 0.10.1 — stale by a full
release, unnoticed for three weeks.

`gradle.properties` is the source of truth: it is where the version is declared once, and gate 1
moves it in the same commit as everything below, so the invariant holds on every commit rather than
only at release time.

**Which version the prose should name**, since "vX is published to Maven Central" is true of the
previous version until gate 3 finishes and of the new one after: it names the version being
prepared, always equal to `gradle.properties`. The alternative is correct only in the minutes
between gate 2 and gate 3 and wrong for the rest of the doc's life.

    python3 check_release_coordinates.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

VERSION = re.compile(r"^version=([0-9]+\.[0-9]+\.[0-9]+)[ \t]*$", re.MULTILINE)
ANY_VERSION = re.compile(r"\bv?([0-9]+\.[0-9]+\.[0-9]+)\b")

# Both published coordinate forms: Maven Central's `group:artifact:version` and JitPack's
# `v`-prefixed tag. Matched by artifact rather than by fence, because README carries one in prose
# outside any code block.
COORDINATE = re.compile(r"musicmeta-[a-z]+:(v?)([0-9]+\.[0-9]+\.[0-9]+)")

COORDINATE_FILES = (
    "README.md",
    "docs/guides/quick-start.md",
    "docs/guides/extension-points.md",
    "docs/guides/android.md",
)

ROADMAP = "ROADMAP.md"
GRADLE_PROPERTIES = "gradle.properties"

# Each guarded region runs from its heading to the next heading of any level. Naming the headings
# rather than counting lines means a rename fails the check loudly instead of silently guarding
# nothing — `### Current Coverage` below names the release each capability landed in, which is
# permanently older and must not be rewritten.
REGIONS = (
    re.compile(r"^## Where We Are\b.*$", re.MULTILINE),
    re.compile(r"^### Unreleased\b.*$", re.MULTILINE),
)


def declared_version(root: Path) -> str | None:
    """The version `gradle.properties` declares, or None when it cannot be read."""
    path = root / GRADLE_PROPERTIES
    if not path.exists():
        return None
    match = VERSION.search(path.read_text(encoding="utf-8"))
    return match.group(1) if match else None


def region_lines(text: str, heading: re.Pattern[str]) -> list[tuple[int, str]] | None:
    """The heading's line and every line to the next heading, 1-indexed. None when absent."""
    match = heading.search(text)
    if not match:
        return None
    lines = text.splitlines()
    start = text[: match.start()].count("\n")
    numbered = [(start + 1, lines[start])]
    for offset, line in enumerate(lines[start + 1 :], start=start + 2):
        if line.startswith("#"):
            break
        numbered.append((offset, line))
    return numbered


def run(root: Path) -> list[str]:
    """All findings for the tree at root, formatted for GitHub annotations."""
    expected = declared_version(root)
    if expected is None:
        return [
            f"::error file={GRADLE_PROPERTIES}::no `version=x.y.z` line, so there is nothing to "
            "check the docs against. Restore it, or fix declared_version()."
        ]

    findings = []
    roadmap = root / ROADMAP
    if roadmap.exists():
        text = roadmap.read_text(encoding="utf-8")
        for heading in REGIONS:
            lines = region_lines(text, heading)
            if lines is None:
                findings.append(
                    f"::error file={ROADMAP}::no heading matching `{heading.pattern}`, so the block "
                    f"naming the published version is unguarded. Restore the heading, or update "
                    "REGIONS in check_release_coordinates.py."
                )
                continue
            for number, line in lines:
                for found in ANY_VERSION.findall(line):
                    if found != expected:
                        findings.append(
                            f"::error file={ROADMAP},line={number}::names {found}, but "
                            f"{GRADLE_PROPERTIES} declares {expected}. This block names the version "
                            "being prepared; see check_release_coordinates.py for why."
                        )

    for rel in COORDINATE_FILES:
        path = root / rel
        if not path.exists():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            for prefix, found in COORDINATE.findall(line):
                if found != expected:
                    findings.append(
                        f"::error file={rel},line={number}::pins musicmeta at {prefix}{found}, but "
                        f"{GRADLE_PROPERTIES} declares {expected}. A consumer copying this line "
                        "gets the wrong release."
                    )
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    findings = run(parser.parse_args().root.resolve())
    for finding in findings:
        print(finding)
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
