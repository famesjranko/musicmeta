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
import re
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

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")
TRIPLE_STRING = re.compile(r'""".*?"""', re.DOTALL)
CHAR_LITERAL = re.compile(r"'(?:\\.|[^'\\])'")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\\n])*"')


def strip_noise(source: str) -> str:
    """Blank out comments and string literals so a `!!` inside one is not a violation.

    Replaces with same-length whitespace runs rather than deleting, so reported line numbers
    still match the original file.
    """

    def blank(match: re.Match[str]) -> str:
        return re.sub(r"[^\n]", " ", match.group(0))

    for pattern in (BLOCK_COMMENT, TRIPLE_STRING, LINE_COMMENT, CHAR_LITERAL, STRING_LITERAL):
        source = pattern.sub(blank, source)
    return source


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
    findings = []
    for lineno, line in enumerate(strip_noise(source).splitlines(), start=1):
        # `!!=` is not the not-null assertion; neither is the `!==` identity operator.
        for match in re.finditer(r"!!", line):
            tail = line[match.end():match.end() + 1]
            if tail not in ("=",):
                findings.append(
                    f"::error file={rel},line={lineno}::`!!` is banned in main sources. "
                    f"Handle the null: use `?:` with a real fallback, an early return, or "
                    f"`requireNotNull(x) {{ \"why this cannot be null\" }}` if it truly cannot be."
                )
                break
    return findings


def check_serializable_placement(path: Path, rel: str, source: str) -> list[str]:
    posix = path.as_posix()
    if not any(marker in posix for marker in SERIALIZABLE_BANNED_DIRS):
        return []
    findings = []
    for lineno, line in enumerate(strip_noise(source).splitlines(), start=1):
        if "@Serializable" in line:
            findings.append(
                f"::error file={rel},line={lineno}::@Serializable does not belong on a provider "
                f"or http type. Keep it on the public payload types (EnrichmentData subtypes, "
                f"EnrichmentIdentifiers) and map this type into one of those instead."
            )
    return findings


def check_file_length(path: Path, rel: str, source: str) -> list[str]:
    lines = len(source.splitlines())
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

    root = Path(args.root) if args.root else Path(__file__).resolve().parent.parent.parent
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
