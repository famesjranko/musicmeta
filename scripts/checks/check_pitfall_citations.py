#!/usr/bin/env python3
"""Fail on a pitfall citation that no longer resolves.

Code comments, tests and docs cite `docs/pitfalls.md` by section number, and nothing else reads
those references — renumbering or deleting a `## N.` heading orphans every one of them silently.
This check collects the numbered headings that exist and fails on any cited number that does not.

Deliberately one job: it does not validate area-heading names (`CLAUDE.md` is the only citer of
those, and it is read every session), and it does not police citation formatting.

    python3 check_pitfall_citations.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PITFALLS = "docs/pitfalls.md"
# Files whose bare section references (no `pitfalls` on the line) are still citations: only the
# file itself — everywhere else a citation names the file on the same line.
BARE_REFERENCE_FILES = (PITFALLS,)

HEADING = re.compile(r"^## (\d+)\.")
SECTION_REF = re.compile("§(\\d+)")

SUFFIXES = (".md", ".yml", ".yaml", ".kt", ".kts", ".py", ".sh", ".toml", ".json")


def valid_ids(root: Path) -> set[str]:
    """The section numbers that exist as `## N.` headings in the pitfalls file."""
    path = root / PITFALLS
    if not path.is_file():
        return set()
    return {m.group(1) for line in path.read_text(encoding="utf-8").splitlines() if (m := HEADING.match(line))}


def citing_files(root: Path) -> list[Path]:
    """Every text file a citation can live in — sources, tests, scripts, docs and configs."""
    return sorted(
        path
        for path in root.rglob("*")
        if path.suffix in SUFFIXES
        and path.is_file()
        and "/build/" not in path.as_posix()
        and "/.git/" not in path.as_posix()
        and "/.claude/" not in path.as_posix()
    )


def run(root: Path) -> list[str]:
    """All findings for the tree at root, formatted for GitHub annotations."""
    ids = valid_ids(root)
    if not ids:
        return [
            f"::error::no `## N.` headings found in {PITFALLS}, so every citation is orphaned. "
            "Restore the headings or fix valid_ids()."
        ]
    known = ", ".join(sorted(ids, key=int))
    findings = []
    for path in citing_files(root):
        rel = path.relative_to(root).as_posix()
        check_bare = rel in BARE_REFERENCE_FILES
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not check_bare and "pitfalls" not in line:
                continue
            for cited in SECTION_REF.findall(line):
                if cited not in ids:
                    findings.append(
                        f"::error file={rel},line={number}::cites pitfall {cited}, which has no "
                        f"`## {cited}.` heading in {PITFALLS} (have: {known}). "
                        "Restore the heading or update the citation."
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
    sys.exit(main())
