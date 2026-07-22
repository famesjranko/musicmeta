#!/usr/bin/env python3
"""Validate a GitHub Release body pins its own version and carries no floating badge.

Release notes are hand-assembled after the tag, so no PR gate sees them. Versionless "latest" badges
(`img.shields.io/maven-central/v/…`, `jitpack.io/v/…svg`) render whatever is newest on *every*
release page, so an old release shows a newer version — the drift this catches. Install coordinates
must instead pin the release's own version.

Note the rules are the inverse of a Play Store validator: here markdown and links are wanted, and a
*pinned* link is the fix — the check forbids the versionless badge and any mismatched coordinate.

Usage:
    python3 validate_release_notes.py <version> [--notes-file PATH]      # notes default to stdin
    gh release view v0.10.1 --json body --jq .body | python3 validate_release_notes.py 0.10.1

Exit codes: 0 = valid, 1 = usage/empty notes, 2 = validation failure.
Importable: validate(notes, version) -> list[str] of error messages (empty = valid).
"""
from __future__ import annotations

import argparse
import re
import sys

# Versionless badges — the exact two the README carries, correct there, wrong pinned to a release.
FLOATING_BADGE = re.compile(
    r"img\.shields\.io/maven-central/v/io\.github\.famesjranko"
    r"|jitpack\.io/v/famesjranko/musicmeta\.svg"
)
# Coordinate forms as they appear in an install block (colon-separated, version-pinned). The optional
# suffix group is captured so `0.10.1-SNAPSHOT` compares unequal to `0.10.1` rather than passing.
_VER = r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"
MAVEN_COORD = re.compile(rf"io\.github\.famesjranko:(musicmeta-[a-z]+):({_VER})")
JITPACK_COORD = re.compile(rf"com\.github\.famesjranko\.musicmeta:(musicmeta-[a-z]+):v({_VER})")


def validate(notes: str, version: str) -> list[str]:
    """Return a list of rule violations (empty means the notes are valid for `version`)."""
    ver = version.removeprefix("v")
    errors: list[str] = []

    if FLOATING_BADGE.search(notes):
        errors.append(
            "Contains a versionless (floating) Maven/JitPack badge — it renders the live-latest "
            "version on every release page. Pin the version instead."
        )

    bad_mvn = sorted({f"{mod}:{v}" for mod, v in MAVEN_COORD.findall(notes) if v != ver})
    if bad_mvn:
        errors.append(f"Maven coordinate(s) not pinned to {ver}: {', '.join(bad_mvn)}")

    bad_jit = sorted({f"{mod}:v{v}" for mod, v in JITPACK_COORD.findall(notes) if v != ver})
    if bad_jit:
        errors.append(f"JitPack coordinate(s) not pinned to v{ver}: {', '.join(bad_jit)}")

    # A correct core coordinate must actually be present — catches a missing/empty install block. The
    # lookahead (not \b) also rejects `0.10.10` and `0.10.1-SNAPSHOT` as satisfying a `0.10.1` pin.
    if not re.search(rf"io\.github\.famesjranko:musicmeta-core:{re.escape(ver)}(?![0-9A-Za-z.\-])", notes):
        errors.append(f"Missing the pinned coordinate io.github.famesjranko:musicmeta-core:{ver}")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate release notes pin the tag version.")
    parser.add_argument("version", help="release version, e.g. 0.10.1 (leading v allowed)")
    parser.add_argument("--notes-file", help="read notes from this file instead of stdin")
    args = parser.parse_args(argv)

    notes = open(args.notes_file, encoding="utf-8").read() if args.notes_file else sys.stdin.read()
    if not notes.strip():
        print("::error::release notes are empty", file=sys.stderr)
        return 1

    errors = validate(notes, args.version)
    for e in errors:
        print(f"::error::{e}", file=sys.stderr)
    if errors:
        return 2
    print(f"Release notes pin {args.version} consistently, no floating badge.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
