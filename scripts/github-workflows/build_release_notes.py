#!/usr/bin/env python3
"""Assemble a GitHub Release body from a CHANGELOG.md version section.

The `## [x.y.z]` section IS the release note: its prose is hand-written and used verbatim. Only the
install coordinates and the compare link are generated, which is exactly what makes them impossible
to leave stale. Length caps keep the section release-note shaped rather than an essay per bug fix —
v0.10.0 and v0.10.1 first shipped 8.6k- and 6.6k-char walls copied from an uncapped changelog.

    python3 build_release_notes.py <version> [--changelog PATH] [--out PATH]

Exit codes: 0 = valid, 1 = file/version not found, 2 = the section violates a cap.
Importable: extract_section(text, version) -> str, build(text, version) -> str.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from validate_release_notes import validate

# Measured against the six hand-rewritten v0.8.2-v0.10.1 notes: prose ran 188-1643 chars with a
# longest line of 375. The total is the load-bearing cap; the per-line cap is a backstop that also
# rejects a pasted 0.10.x-era changelog bullet (786-1538 chars) on sight.
MAX_SECTION_CHARS = 3000
MAX_LINE_CHARS = 400

REPO = "famesjranko/musicmeta"
GROUP = "io.github.famesjranko"
JITPACK_GROUP = "com.github.famesjranko.musicmeta"
MODULES = ("musicmeta-core", "musicmeta-okhttp", "musicmeta-android")
MODULE_NOTES = {"musicmeta-okhttp": "  // Optional: OkHttp adapter", "musicmeta-android": " // Optional: Android"}

VERSION_HEADING = re.compile(r"^##\s+\[([0-9]+\.[0-9]+\.[0-9]+)\]")


class BuildError(Exception):
    """Raised when the version section is missing, empty, or over a cap."""


def released_versions(text: str) -> list[str]:
    """Every pinned `## [x.y.z]` version in file order. `## [Unreleased]` is skipped by the digit."""
    return [m.group(1) for line in text.splitlines() if (m := VERSION_HEADING.match(line))]


def extract_section(text: str, version: str) -> str:
    """Return the body of the `## [version]` section, without its heading line.

    Ends at the next `## ` heading. Raises BuildError if the version is absent or its body is empty.
    """
    ver = version.removeprefix("v")
    collected: list[str] = []
    found = False

    for line in text.splitlines():
        if found and line.startswith("## "):
            break
        if found:
            collected.append(line)
            continue
        match = VERSION_HEADING.match(line)
        if match and match.group(1) == ver:
            found = True

    if not found:
        raise BuildError(f"CHANGELOG has no '## [{ver}]' heading — pin the [Unreleased] section first.")

    while collected and not collected[0].strip():
        collected.pop(0)
    while collected and not collected[-1].strip():
        collected.pop()

    if not collected:
        raise BuildError(f"The '## [{ver}]' section is empty — it is the release note, so write it.")

    return "\n".join(collected)


def check_caps(section: str, version: str) -> None:
    """Raise BuildError if the section exceeds either length cap, naming the overage."""
    errors: list[str] = []

    if len(section) > MAX_SECTION_CHARS:
        errors.append(
            f"section is {len(section)} chars (limit {MAX_SECTION_CHARS}) — "
            f"trim {len(section) - MAX_SECTION_CHARS}. One line per change; rationale goes in the issue or PR."
        )

    for number, line in enumerate(section.splitlines(), start=1):
        if len(line) > MAX_LINE_CHARS:
            errors.append(
                f"line {number} is {len(line)} chars (limit {MAX_LINE_CHARS}) — "
                f"that is a paragraph, not a bullet: {line[:60]}..."
            )

    if errors:
        raise BuildError(
            f"The '## [{version}]' section is not release-note shaped:\n"
            + "\n".join(f"  - {e}" for e in errors)
        )


def installation_block(version: str) -> str:
    """Generated, so a coordinate cannot be left pinned to a previous release."""
    maven = "\n".join(f'implementation("{GROUP}:{m}:{version}"){MODULE_NOTES.get(m, "")}' for m in MODULES)
    jitpack = "\n".join(f'implementation("{JITPACK_GROUP}:{m}:v{version}"){MODULE_NOTES.get(m, "")}' for m in MODULES)
    return (
        "## Installation\n\n"
        f"**Maven Central** — [`{GROUP}:musicmeta-core:{version}`]"
        f"(https://central.sonatype.com/artifact/{GROUP}/musicmeta-core/{version})\n\n"
        f"```kotlin\n{maven}\n```\n\n"
        f"**JitPack** — [`v{version}`](https://jitpack.io/#{REPO}/v{version})\n\n"
        f"```kotlin\n{jitpack}\n```"
    )


def build(text: str, version: str) -> str:
    """Assemble the full release body. Raises BuildError if the section is unusable."""
    ver = version.removeprefix("v")
    section = extract_section(text, ver)
    check_caps(section, ver)

    parts = [section, installation_block(ver)]

    # The previous release is the next pinned heading below this one. A first release has none.
    versions = released_versions(text)
    index = versions.index(ver)
    if index + 1 < len(versions):
        previous = versions[index + 1]
        parts.append(f"**Full Changelog**: https://github.com/{REPO}/compare/v{previous}...v{ver}")

    body = "\n\n".join(parts) + "\n"

    # The generator must not be able to emit notes the release-notes check would reject.
    if problems := validate(body, ver):
        raise BuildError("assembled notes failed validate_release_notes:\n" + "\n".join(f"  - {p}" for p in problems))

    return body


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Assemble a GitHub Release body from CHANGELOG.md.")
    parser.add_argument("version", help="release version, e.g. 0.11.0 (leading v allowed)")
    parser.add_argument("--changelog", help="path to CHANGELOG.md (default: cwd, then repo root)")
    parser.add_argument("--out", help="write the body here instead of stdout")
    args = parser.parse_args(argv)

    if args.changelog:
        changelog = Path(args.changelog)
    else:
        # The script lives in scripts/github-workflows/, so the repo root is two parents up.
        candidates = [Path.cwd() / "CHANGELOG.md", Path(__file__).resolve().parent.parent.parent / "CHANGELOG.md"]
        changelog = next((p for p in candidates if p.exists()), candidates[0])

    if not changelog.exists():
        print(f"::error::CHANGELOG not found at {changelog}", file=sys.stderr)
        return 1

    try:
        body = build(changelog.read_text(encoding="utf-8"), args.version)
    except BuildError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1 if "no '## [" in str(e) else 2

    if args.out:
        Path(args.out).write_text(body, encoding="utf-8")
        print(f"Wrote {len(body)} chars of release notes for {args.version} to {args.out}")
    else:
        sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
