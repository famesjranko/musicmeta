#!/usr/bin/env python3
"""Fail on `@Test` bodies with no given/when/then structure, or with malformed labels.

`CLAUDE.md`'s rule: `// Given — <what is set up>`, `// When — <the one call>`, `// Then — <what
must hold>`, each on its own line, em dash and a clause on every one. Neither ktlint nor
type-resolved detekt can express this — it is house test-writing convention, not general Kotlin
advice or a bug pattern — so it stayed a convention nobody checked until two MusicBrainz test files
landed with no given/when/then at all and passed every gate (03).

A `@Test`-annotated function's body is the text window from its `@Test` line to the next `@Test`
line, or the end of the file, whichever comes first. `@Test`-annotated functions cannot nest in
Kotlin, so that boundary is exact without brace-matching or any other structural parsing (05).

Test sources only (`*/src/test/**/*.kt`) — this has no opinion on main sources, and does not fire on
a helper function that happens to mention "given" in prose.

    python3 check_test_shape.py [--root PATH]
    python3 check_test_shape.py --file PATH   # one file, for format-on-write.sh
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TEST_ANNOTATION_RE = re.compile(r"^\s*@Test\b")
LABEL_WORD_RE = re.compile(r"\b(Given|When|Then)\b")
# A well-formed label: `// <Word> — <clause>`, the label alone on its line. `\S` after the dash
# requires a real clause, not just trailing whitespace. `-` and `—` are both accepted: a plain
# hyphen carries the same "label, then its clause" structure as the em dash `CLAUDE.md` names, and
# a repo-wide audit found only the em dash form worth mechanising as a distinction from "no dash at
# all" — not from each other.
WELL_FORMED_LABEL_RE = re.compile(r"^\s*//\s*(Given|When|Then)\s*[-—]\s*\S")
# A line that opens with `//` and names a Given/When/Then word at all — this is the trigger for
# "this line is trying to be a label," so it's what bare-label and combined-label violations are
# both diagnosed against.
LABEL_LINE_RE = re.compile(r"^\s*//.*\b(Given|When|Then)\b")

NO_GIVEN_FIX = (
    "`@Test` body has no `// Given —` line. Every test states what it sets up, does, and checks: "
    "`// Given — <what is set up>`, `// When — <the one call>`, `// Then — <what must hold>`."
)
BARE_LABEL_FIX = (
    "given/when/then label is missing the em dash and a clause. Use `// Given — <what is set "
    "up>` (or When/Then), not a bare label — the clause is what makes the label discoverable "
    "without grepping."
)
COMBINED_LABEL_FIX = (
    "one comment line names more than one of Given/When/Then. Each label goes on its own line, "
    "even when the setup and the call are one line of code."
)


def test_sources(root: Path) -> list[Path]:
    return sorted(path for path in root.glob("*/src/test/**/*.kt") if "/build/" not in path.as_posix())


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def check_window(rel: str, lines: list[str], start: int, end: int) -> list[str]:
    """Check one `@Test` function's body, `lines[start:end]` (0-indexed, half-open)."""
    findings = []
    has_given = False
    for lineno in range(start, end):
        line = lines[lineno]
        if not LABEL_LINE_RE.match(line):
            continue
        words = LABEL_WORD_RE.findall(line)
        if len(words) > 1:
            findings.append(error(rel, lineno + 1, COMBINED_LABEL_FIX))
            continue
        if not WELL_FORMED_LABEL_RE.match(line):
            findings.append(error(rel, lineno + 1, BARE_LABEL_FIX))
            continue
        if words[0] == "Given":
            has_given = True
    if not has_given:
        findings.append(error(rel, start + 1, NO_GIVEN_FIX))
    return findings


def check_file(rel: str, path: Path) -> list[str]:
    findings = []
    lines = path.read_text(encoding="utf-8").split("\n")
    test_lines = [i for i, line in enumerate(lines) if TEST_ANNOTATION_RE.match(line)]
    for i, start in enumerate(test_lines):
        end = test_lines[i + 1] if i + 1 < len(test_lines) else len(lines)
        findings.extend(check_window(rel, lines, start, end))
    return findings


def run(root: Path) -> list[str]:
    findings = []
    for path in test_sources(root):
        findings.extend(check_file(path.relative_to(root).as_posix(), path))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Enforce given/when/then test structure.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    parser.add_argument("--file", help="check a single file instead of the whole repo (format-on-write.sh)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent

    if args.file:
        path = Path(args.file).resolve()
        findings = check_file(path.as_posix(), path) if path.is_file() else []
        for finding in findings:
            print(finding, file=sys.stderr)
        if findings:
            print(f"\n{len(findings)} test-shape violation(s).", file=sys.stderr)
            return 2
        return 0

    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} test-shape violation(s).", file=sys.stderr)
        return 2

    print(f"Test shape clean across {len(test_sources(root))} test sources.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
