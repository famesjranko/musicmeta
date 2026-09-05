#!/usr/bin/env python3
"""Fail on a pitfall citation that no longer resolves.

Code comments, tests and docs cite `docs/pitfalls.md` by section number, and nothing else reads
those references — renumbering or deleting a `## N.` heading orphans every one of them silently.
This check collects the numbered headings that exist and fails on any cited number that does not.

It also fails on a number used twice. Two changes numbering "a new section 32" independently is not
hypothetical — it happened, both landed, and nothing noticed: the headings are hand-assigned, they
sit in different areas of the file, so the two edits merge cleanly and every later citation of that
number silently means two things. Collecting into a set, as this check did, is exactly what hides it.

Deliberately one job otherwise: it does not validate area-heading names (`CLAUDE.md` is the only
citer of those, and it is read every session), and it does not police citation formatting.

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

# Agent worktrees are full checkouts on other branches, where `docs/pitfalls.md` may legitimately
# be numbered differently — a citation there resolves against that branch's doc, not this one's.
# Scoped to `worktrees/` rather than all of `.claude/`: `commands/` and `settings.json` are
# committed wiring, and a citation in them orphans exactly like one anywhere else.
#
# Tested against the path *relative to `root`*, not the absolute path: `root` itself can be a
# worktree (`<repo>/.claude/worktrees/agent-*/`), and its absolute path then contains this
# substring too, excluding every file the scan was asked to read instead of only the nested ones.
AGENT_WORKTREES = ".claude/worktrees/"


def is_agent_worktree(path: Path, root: Path) -> bool:
    """Whether `path` sits under an agent worktree nested inside `root`."""
    return AGENT_WORKTREES in path.relative_to(root).as_posix()


# `AGENTS.md` is a symlink to `CLAUDE.md`, so following it reads the same bytes twice and reports
# any orphan in that file twice. Skipping symlinks costs nothing: the target is either inside the
# tree, and already scanned under its own name, or outside it, and not this repo's content.


def heading_lines(root: Path) -> list[tuple[str, int]]:
    """Every `## N.` heading in the pitfalls file, as (number, line), in the order they appear."""
    path = root / PITFALLS
    if not path.is_file():
        return []
    lines = path.read_text(encoding="utf-8").splitlines()
    return [(m.group(1), number) for number, line in enumerate(lines, start=1) if (m := HEADING.match(line))]


def valid_ids(root: Path) -> set[str]:
    """The section numbers that exist as `## N.` headings in the pitfalls file."""
    return {number for number, _ in heading_lines(root)}


def duplicate_findings(root: Path) -> list[str]:
    """A number carried by more than one heading, which makes every citation of it ambiguous."""
    seen: dict[str, list[int]] = {}
    for number, line in heading_lines(root):
        seen.setdefault(number, []).append(line)
    return [
        f"::error file={PITFALLS},line={lines[1]}::section {number} is used by {len(lines)} headings "
        f"(lines {', '.join(str(line) for line in lines)}), so every citation of it names more than "
        "one pitfall. Renumber all but the first to the next unused number."
        for number, lines in sorted(seen.items(), key=lambda item: int(item[0]))
        if len(lines) > 1
    ]


def citing_files(root: Path) -> list[Path]:
    """Every text file a citation can live in — sources, tests, scripts, docs and configs."""
    return sorted(
        path
        for path in root.rglob("*")
        if path.suffix in SUFFIXES
        and path.is_file()
        and not path.is_symlink()
        and "/build/" not in path.as_posix()
        and "/.git/" not in path.as_posix()
        and not is_agent_worktree(path, root)
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
    findings = duplicate_findings(root)
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
