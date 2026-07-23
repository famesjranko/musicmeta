#!/usr/bin/env python3
"""Fail if a STORIES.md entry exceeds the length cap.

The CHANGELOG cap is enforced because the release consumes it; STORIES has no consumer, so without
this it would be an unenforced ask — which is exactly what produced the release-note walls the
CHANGELOG cap exists to stop. Entries before GRANDFATHERED_BEFORE are left alone: the rule applies
going forward, and rewriting history costs more than it returns.

    python3 check_doc_caps.py [--stories PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Anchored on the slim end of the existing distribution (the useful-but-tight entries run 1210-1566),
# not its 2086 median — a median just codifies the drift. A decision needing more than this belongs
# in the PR, or is really two decisions. The 2026-07-22 entries predate the rule and are left alone;
# the entry that prompted it meets the cap anyway, as the worked example.
MAX_ENTRY_CHARS = 1500
GRANDFATHERED_BEFORE = "2026-07-23"

ENTRY = re.compile(r"^### (\d{4}-\d{2}-\d{2}):")


def oversized(text: str) -> list[tuple[str, int]]:
    """Return (heading, length) for each in-scope entry over the cap."""
    entries: list[tuple[str, str, list[str]]] = []
    for line in text.splitlines():
        if match := ENTRY.match(line):
            entries.append((match.group(1), line, []))
        elif entries:
            entries[-1][2].append(line)

    return [
        (heading, size)
        for date, heading, body in entries
        if date >= GRANDFATHERED_BEFORE and (size := len(heading) + 1 + sum(len(b) + 1 for b in body)) > MAX_ENTRY_CHARS
    ]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Enforce the STORIES.md entry length cap.")
    parser.add_argument("--stories", help="path to STORIES.md (default: repo root)")
    args = parser.parse_args(argv)

    path = Path(args.stories) if args.stories else Path(__file__).resolve().parent.parent.parent / "STORIES.md"
    if not path.exists():
        print(f"::error::STORIES not found at {path}", file=sys.stderr)
        return 1

    over = oversized(path.read_text(encoding="utf-8"))
    for heading, size in over:
        print(
            f"::error::{heading} is {size} chars (limit {MAX_ENTRY_CHARS}) — trim {size - MAX_ENTRY_CHARS}. "
            f"Detail belongs in the PR; keep the decision, the reversal and the rejected options.",
            file=sys.stderr,
        )
    if over:
        return 2
    print(f"STORIES entries from {GRANDFATHERED_BEFORE} are all within {MAX_ENTRY_CHARS} chars.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
