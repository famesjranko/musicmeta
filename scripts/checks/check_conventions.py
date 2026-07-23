#!/usr/bin/env python3
"""Fail on house code conventions that no other gate enforces.

ktlint owns formatting and `apiCheck` owns the public ABI. These three rules are the ones that
were prose in CLAUDE.md with nothing to fail on them, which meant an agent had to read and obey
them rather than being told. Each message names the fix, not just the violation — the audience
is usually an agent, and an observation without a next move wastes a round trip.

Main sources only. Test sources are excluded deliberately and that exclusion is recorded in
ARCHITECTURE.md under "Not enforced" rather than left implicit here.

    python3 check_conventions.py [--root PATH]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# CLAUDE.md sets 200 as the target and 300 as the max. The four files already over it predate the
# rule; they are listed rather than reformatted so the gate can ship green, and printed on every
# run so they stay visible instead of becoming a permanent silent exemption.
MAX_FILE_LINES = 300
GRANDFATHERED_LONG_FILES = {
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/DefaultEnrichmentEngine.kt",
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/DeezerProvider.kt",
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/GenreTaxonomy.kt",
    "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/musicbrainz/MusicBrainzEnricher.kt",
}

# @Serializable belongs on public API payload types only. On a provider model or an http/ type it
# silently widens the serialized surface consumers cache, so a later rename breaks their stored
# JSON rather than their compile.
SERIALIZABLE_BANNED_DIRS = ("/provider/", "/http/")

# A hand-written scanner, not a regex. Kotlin nests: a string can hold a `${...}` template, that
# template holds code, and that code can hold another string. Regex cannot express that, and two
# attempts proved it — five sequential passes let the `//` in "https://host/" blank real code, then
# a single alternation could not span the nested quote in `"${enc(id!!, "UTF-8")}"`.
#
# Every branch below exists because a review found a case that silently hid a real `!!`. The two
# least obvious are Kotlin lexical rules rather than nesting: a raw string closes on the LAST `"""`
# of a quote run, and a backtick-escaped identifier may contain `'` or `"`. Both let a stray
# delimiter reach code position, where it opened a context that ran to end of file.
#
# Returns a same-length mask so reported line numbers always match the source.


def _code_mask(source: str) -> list[bool]:
    """True where a character is code, False inside comments and string literal text."""
    n = len(source)
    mask = [True] * n
    stack: list[str] = []  # "string" | "raw" | "template" | "brace"
    i = 0

    def blank(start: int, stop: int) -> None:
        for k in range(start, min(stop, n)):
            mask[k] = False

    while i < n:
        ctx = stack[-1] if stack else None

        # Inside a string literal, everything is text until it ends or a template opens.
        if ctx in ("string", "raw"):
            # Escapes exist in normal strings only; a raw string treats backslash literally, so
            # `"""\"""` ends where it looks like it ends.
            if ctx == "string" and source[i] == "\\":
                blank(i, i + 2)
                i += 2
                continue
            # `\$` is a literal dollar, already consumed above — so reaching here means a real
            # template. Its expression is code and must stay visible.
            if source[i] == "$" and i + 1 < n and source[i + 1] == "{":
                stack.append("template")
                blank(i, i + 2)
                i += 2
                continue
            if ctx == "raw" and source[i] == '"':
                # Maximal munch: Kotlin closes a raw string on the LAST `"""` of a quote run, so
                # `"""he said "hi""""` ends at the 4th quote with `"` as content. Popping on the
                # first three left a stray quote in code position, which opened a normal string
                # that swallowed the rest of the file.
                run = 0
                while i + run < n and source[i + run] == '"':
                    run += 1
                if run >= 3:
                    stack.pop()
                blank(i, i + run)
                i += run
                continue
            if ctx == "string" and source[i] == '"':
                stack.pop()
                blank(i, i + 1)
                i += 1
                continue
            blank(i, i + 1)
            i += 1
            continue

        # Code position — including inside a template, which is why a nested string works.
        if source.startswith("//", i):
            stop = source.find("\n", i)
            stop = n if stop < 0 else stop
            blank(i, stop)
            i = stop
            continue

        if source.startswith("/*", i):
            # Kotlin block comments nest, unlike Java's. A lazy regex stopped at the first `*/`
            # and scanned the remainder of a commented-out region as live code.
            depth = 1
            blank(i, i + 2)
            i += 2
            while i < n and depth:
                if source.startswith("/*", i):
                    depth += 1
                    blank(i, i + 2)
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    blank(i, i + 2)
                    i += 2
                else:
                    blank(i, i + 1)
                    i += 1
            continue

        if source[i] == "`":
            # A backtick-escaped identifier is a name, not code to scan. kotlinc rejects `/` in
            # one, so it cannot hide a comment — but `'`, `"`, `$` and braces are all legal and
            # would otherwise open a context that runs to EOF. Blanked, so `` fun `not!!really` ``
            # is also not reported as a violation.
            blank(i, i + 1)
            i += 1
            while i < n and source[i] != "`":
                blank(i, i + 1)
                i += 1
            if i < n:
                blank(i, i + 1)
                i += 1
            continue

        if source.startswith('"""', i):
            stack.append("raw")
            blank(i, i + 3)
            i += 3
            continue

        if source[i] == '"':
            stack.append("string")
            blank(i, i + 1)
            i += 1
            continue

        if source[i] == "'":
            blank(i, i + 1)
            i += 1
            while i < n:
                if source[i] == "\\":
                    blank(i, i + 2)
                    i += 2
                    continue
                blank(i, i + 1)
                i += 1
                if source[i - 1] == "'":
                    break
            continue

        # Brace tracking so the `}` closing a template is told apart from one closing a lambda
        # inside it: `"${list.map { it }}"`.
        if source[i] == "{" and ctx in ("template", "brace"):
            stack.append("brace")
        elif source[i] == "}" and ctx == "brace":
            stack.pop()
        elif source[i] == "}" and ctx == "template":
            stack.pop()
            blank(i, i + 1)
        i += 1

    return mask


def strip_noise(source: str) -> str:
    """Blank comments and string text so a `!!` inside one is not a violation.

    Same length and same newline positions as the input, so every reported line number still
    matches the original file.
    """
    mask = _code_mask(source)
    return "".join(c if (mask[i] or c == "\n") else " " for i, c in enumerate(source))


def main_sources(root: Path) -> list[Path]:
    """Main sources of the published modules.

    `demo/` is excluded for the same reason ktlint skips it: it is a separate composite build whose
    job is to compile against the published surface like an external consumer, not to match house
    style. Holding it to these rules would make the canary about us instead of about consumers.
    """
    return sorted(
        path
        for path in root.glob("*/src/main/**/*.kt")
        if "/build/" not in path.as_posix() and not path.as_posix().startswith(f"{root.as_posix()}/demo/")
    )


def check_no_double_bang(path: Path, rel: str, source: str) -> list[str]:
    """Report every line whose code contains `!!`.

    Any `!!` counts. An earlier version skipped `!!=` to avoid the `!==` operator, but `!==`
    contains no `!!` at all, so that guard caught nothing it meant to and did skip `u!!==v`,
    which is a real violation. The remaining false positive is repeated negation (`!!flag`),
    which is rare and loud; a false negative here is silent, and silent is the worse failure
    for a gate.
    """
    findings = []
    for lineno, line in enumerate(strip_noise(source).split("\n"), start=1):
        if "!!" in line:
            findings.append(
                f"::error file={rel},line={lineno}::`!!` is banned in main sources. "
                f"Handle the null: use `?:` with a real fallback, an early return, or "
                f'`requireNotNull(x) {{ "why this cannot be null" }}` if it truly cannot be.'
            )
    return findings


def check_serializable_placement(path: Path, rel: str, source: str) -> list[str]:
    posix = path.as_posix()
    if not any(marker in posix for marker in SERIALIZABLE_BANNED_DIRS):
        return []
    findings = []
    for lineno, line in enumerate(strip_noise(source).split("\n"), start=1):
        if "@Serializable" in line:
            findings.append(
                f"::error file={rel},line={lineno}::@Serializable does not belong on a provider "
                f"or http type. Keep it on the public payload types (EnrichmentData subtypes, "
                f"EnrichmentIdentifiers) and map this type into one of those instead."
            )
    return findings


def check_file_length(path: Path, rel: str, source: str) -> list[str]:
    # split("\n"), not splitlines(): the latter also breaks on \f, \x85 and \u2028, so a form
    # feed — valid Kotlin whitespace — would count as a line here but not in GitHub's annotations.
    parts = source.split("\n")
    lines = len(parts) - 1 if parts and parts[-1] == "" else len(parts)
    if lines <= MAX_FILE_LINES or rel in GRANDFATHERED_LONG_FILES:
        return []
    return [
        f"::error file={rel},line={MAX_FILE_LINES}::{rel} is {lines} lines (max "
        f"{MAX_FILE_LINES}). Split it — usually one responsibility has outgrown the file, so "
        f"move that out rather than shaving lines off the rest."
    ]


CHECKS = (check_no_double_bang, check_serializable_placement, check_file_length)


def run(root: Path) -> list[str]:
    findings = []
    for path in main_sources(root):
        rel = path.relative_to(root).as_posix()
        source = path.read_text(encoding="utf-8")
        for check in CHECKS:
            findings.extend(check(path, rel, source))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Enforce house code conventions.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    # Resolved, because the demo/ exclusion compares absolute path prefixes — with `--root .`
    # an unresolved root makes that comparison fail and silently starts scanning demo/.
    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} convention violation(s).", file=sys.stderr)
        return 2

    scanned = len(main_sources(root))
    print(f"Conventions clean across {scanned} main sources.")
    if GRANDFATHERED_LONG_FILES:
        print(f"Grandfathered over {MAX_FILE_LINES} lines (not exemptions — shrink when touched):")
        for rel in sorted(GRANDFATHERED_LONG_FILES):
            print(f"  {rel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
