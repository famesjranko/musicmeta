#!/usr/bin/env python3
"""Fail on `@Test` bodies with no given/when/then structure, or with malformed labels.

`CLAUDE.md`'s rule: `// Given - <what is set up>`, `// When - <the one call>`, `// Then - <what
must hold>`, each on its own line, a hyphen and a clause on every one. No built-in ktlint or detekt
rule expresses this — it is house test-writing convention, not general Kotlin advice or a bug
pattern — so it stayed a convention nobody checked until two MusicBrainz test files landed with no
given/when/then at all and passed every gate (03).

A custom detekt rule *could* express it exactly, over PSI and with no type resolution, and detekt is
already in the build. It was rejected on cost, not capability: the write-time hook wants sub-second
feedback on one file and detekt is a JVM start, and a ruleset means a module, a jar, and permanent
detekt-API coupling. That trade is worth revisiting if the limitation below ever starts firing.

A `@Test`-annotated function's body is the text window from its `@Test` line to the next `@Test`
line or the next top-level `class`/`object` declaration, whichever comes first. `@Test`-annotated
functions cannot nest in Kotlin, so a window never starts inside another one; the declaration bound
is what stops the last `@Test` in a multi-class file from swallowing the next class's helpers.
Neither bound needs brace-matching or any other structural parsing (05).

Kotlin test sources only (`*/src/test/**/*.kt`), on both entry points — this has no opinion on main
sources, and does not fire on a helper function that happens to mention "given" in prose.

Known limitation: the scan is line-based and does not know what a string literal is, so Kotlin
fixture text inside a `\"\"\"` raw string is read as source. A `// Given` in one is graded as a
label, a `class` at column 0 closes the enclosing window early, and — the case that fails quiet —
fixture text reading as a *well-formed* label satisfies the Given check, so that test passes
unread. `check_raw_string_content` is the guard: it tracks delimiter parity and complains about
exactly those lines rather than trying to skip them, so the quiet case is now loud.

Skipping was tried twice and is what the guard is deliberately not. Parity cannot tell an opener
from a `\"\"\"` a comment mentions, nor from a `//` inside an ordinary string literal; a stripper
that guesses wrong sticks open and blanks the rest of the file, `@Test` lines included, reporting
it clean without reading it. That shipped once and hid 43 of one file's 49 tests behind a green
gate. A tripwire that guesses wrong costs one visible error the author fixes by rewording. Same
imprecise signal, opposite failure direction — which is the only property that matters in a gate.

    python3 check_test_shape.py [--root PATH]
    python3 check_test_shape.py --file PATH   # one file, for format-on-write.sh
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

TEST_ANNOTATION_RE = re.compile(r"^\s*@Test\b")
# A top-level `class`/`object` closes the previous `@Test`'s window. Anchored at column 0 so a
# nested or local declaration inside a test body doesn't truncate the window it belongs to.
DECLARATION_RE = re.compile(r"^(?:\w+\s+)*(?:class|object|interface)\b")
# A label line: `//` then a Given/When/Then word in *label position* — the first thing after the
# slashes. Anchoring here is what keeps prose that merely mentions one of the words ("// the When
# branch is taken below") out of the check entirely; an unanchored match reports correct code and
# offers no opt-out. A comment that *opens* with a label word is still read as a label — that case
# is genuinely ambiguous, and rewording it is the opt-out.
LABEL_LINE_RE = re.compile(r"^\s*//\s*(Given|When|Then)\b")
# Two label words in label position, separated by a slash, comma, ampersand, "and", or whitespace —
# `// Given / When - x`. Only label position counts: a well-formed `// Then - the When branch ran`
# names two of the words but is not a combined label.
COMBINED_LABEL_RE = re.compile(r"^\s*//\s*(?:Given|When|Then)(?:\s*[/,&]\s*|\s+and\s+|\s+)(?:Given|When|Then)\b")
# A well-formed label: `// <Word> - <clause>`, the label alone on its line, one space either side
# of the hyphen. `\S` after it requires a real clause, not just trailing whitespace. Spelling the
# separator out as a literal ` - ` rather than a permissive `\s*-\s*` is what rejects the near
# misses — `--`, `->`, `-clause` — each of which is a form nobody chose and every one of which a
# looser pattern waves through.
WELL_FORMED_LABEL_RE = re.compile(r"^\s*//\s*(Given|When|Then) - \S")

NO_GIVEN_FIX = (
    "`@Test` body has no `// Given -` line. Every test states what it sets up, does, and checks: "
    "`// Given - <what is set up>`, `// When - <the one call>`, `// Then - <what must hold>`."
)
BARE_LABEL_FIX = (
    "given/when/then label is missing the hyphen and a clause. Use `// Given - <what is set up>` "
    "(or When/Then), not a bare label — the clause is what makes the label discoverable without "
    "grepping."
)
COMBINED_LABEL_FIX = (
    "one comment line names more than one of Given/When/Then. Each label goes on its own line, "
    "even when the setup and the call are one line of code."
)
RAW_STRING_FIX = (
    'a given/when/then label or a column-0 declaration appears to sit inside a `"""` raw '
    "string, where this check reads it as source and can misjudge it. Reword the fixture text, or "
    "indent the declaration, so the scan and the compiler agree about what this line is."
)

TEST_SOURCE_GLOB = "*/src/test/**/*.kt"


def test_sources(root: Path) -> list[Path]:
    return sorted(path for path in root.glob(TEST_SOURCE_GLOB) if "/build/" not in path.as_posix())


def is_test_source(path: Path, root: Path) -> bool:
    """Whether `--file` should check this path, matching `test_sources()`'s scope exactly.

    The two entry points must agree: a file the gate ignores must not fail at write-time, and a
    file the gate checks must not pass there. This spells `TEST_SOURCE_GLOB` out against the same
    root rather than substring-testing for `/src/test/`: the two look equivalent but are not, since
    the glob's leading `*` admits exactly one module directory below the root while a substring
    match admits any depth — an agent worktree under `.claude/`, say, which the gate never walks.
    (`PurePath.match` is not the shortcut it appears to be either: it does not anchor `**` the way
    `glob` does.)
    """
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        return False
    return (
        len(parts) >= 4 and parts[1:3] == ("src", "test") and path.suffix == ".kt" and "/build/" not in path.as_posix()
    )


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def check_window(rel: str, lines: list[str], start: int, end: int) -> list[str]:
    """Check one `@Test` function's body, `lines[start:end]` (0-indexed, half-open)."""
    findings = []
    has_given = False
    for lineno in range(start, end):
        line = lines[lineno]
        match = LABEL_LINE_RE.match(line)
        if not match:
            continue
        if COMBINED_LABEL_RE.match(line):
            findings.append(error(rel, lineno + 1, COMBINED_LABEL_FIX))
            continue
        if not WELL_FORMED_LABEL_RE.match(line):
            findings.append(error(rel, lineno + 1, BARE_LABEL_FIX))
            continue
        if match.group(1) == "Given":
            has_given = True
    if not has_given:
        findings.append(error(rel, start + 1, NO_GIVEN_FIX))
    return findings


def window_end(lines: list[str], start: int, limit: int) -> int:
    """Where the `@Test` window opening at `start` ends, at or before `limit`."""
    for lineno in range(start + 1, limit):
        if DECLARATION_RE.match(lines[lineno]):
            return lineno
    return limit


def check_raw_string_content(rel: str, lines: list[str]) -> list[str]:
    """Report lines this scan is liable to misread, tracking `\"\"\"` parity across the file.

    A tripwire, not a filter — it only ever adds a finding, and no other check consults it. That
    distinction is the whole design. The same parity tracking used to *skip* what it thought was
    string content, and its wrong guesses were unbounded: a comment merely naming the delimiter
    opened a string that never closed, so every line below went unread and the file passed. Used
    to complain instead, a wrong guess costs one visible error on one line, and the author's fix is
    to reword — the same opt-out the label anchoring already relies on.
    """
    findings = []
    inside = False
    for lineno, line in enumerate(lines):
        if inside and (LABEL_LINE_RE.match(line) or DECLARATION_RE.match(line)):
            findings.append(error(rel, lineno + 1, RAW_STRING_FIX))
        if line.count('"""') % 2 == 1:
            inside = not inside
    return findings


def check_file(rel: str, path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").split("\n")
    findings = check_raw_string_content(rel, lines)
    test_lines = [i for i, line in enumerate(lines) if TEST_ANNOTATION_RE.match(line)]
    for i, start in enumerate(test_lines):
        limit = test_lines[i + 1] if i + 1 < len(test_lines) else len(lines)
        findings.extend(check_window(rel, lines, start, window_end(lines, start, limit)))
    return findings


def run(root: Path) -> list[str]:
    findings = []
    for path in test_sources(root):
        findings.extend(check_file(path.relative_to(root).as_posix(), path))
    return findings


def report(findings: list[str]) -> int:
    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} test-shape violation(s).", file=sys.stderr)
        return 2
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Enforce given/when/then test structure.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    parser.add_argument("--file", help="check a single file instead of the whole repo (format-on-write.sh)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent

    if args.file:
        path = Path(args.file).resolve()
        if not path.is_file() or not is_test_source(path, root):
            return 0
        return report(check_file(path.as_posix(), path))

    exit_code = report(run(root))
    if exit_code == 0:
        print(f"Test shape clean across {len(test_sources(root))} test sources.")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
