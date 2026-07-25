#!/usr/bin/env python3
"""Assemble a GitHub Release body from a CHANGELOG.md version section.

The `## [x.y.z]` section IS the release note: its prose is hand-written and used verbatim. Only the
install coordinates and the compare link are generated, which is exactly what makes them impossible
to leave stale. Length caps keep the section release-note shaped rather than an essay per bug fix —
v0.10.0 and v0.10.1 first shipped 8.6k- and 6.6k-char walls copied from an uncapped changelog.

    python3 build_release_notes.py <version> [--changelog PATH] [--out PATH]
    python3 build_release_notes.py Unreleased    # caps only, no build — what ./check runs

Exit codes: 0 = valid, 1 = file/section not found, 2 = the section violates a cap. Both modes.
Importable: extract_section(text, version) -> str, build(text, version) -> str,
check_unreleased(text) -> None.
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


def section_body(text: str, label: str) -> str:
    """Return the body of the `## [label]` section, without its heading line.

    `label` is matched literally — `0.11.0` or `Unreleased` — so finding a section never consults
    `released_versions()`. That separation is the point: the caps gate has to reach `[Unreleased]`,
    and widening VERSION_HEADING to let it would put `Unreleased` in the released-version list and
    generate a `compare/vUnreleased...v0.11.0` link in the next real release.

    Ends at the next `## ` heading. Raises BuildError if the heading is absent; returns "" if the
    heading is there with nothing under it, because whether that is an error depends on the caller.
    """
    # Same shape as VERSION_HEADING, with the label escaped in place of the semver group, so the two
    # matchers cannot disagree about what counts as a heading. `##  [0.11.0]` must not be a released
    # version that no one can then find the body of.
    heading = re.compile(r"^##\s+\[" + re.escape(label) + r"\]")
    collected: list[str] = []
    found = False

    for line in text.splitlines():
        if found and line.startswith("## "):
            break
        if found:
            collected.append(line)
            continue
        if heading.match(line):
            found = True

    if not found:
        raise BuildError(f"CHANGELOG has no '## [{label}]' heading — pin the [Unreleased] section first.")

    while collected and not collected[0].strip():
        collected.pop(0)
    while collected and not collected[-1].strip():
        collected.pop()

    return "\n".join(collected)


def extract_section(text: str, version: str) -> str:
    """The body of a pinned `## [x.y.z]` section, without its heading line.

    Empty is an error here: the section IS the release note, so a release with nothing in it is a
    release with nothing to say.
    """
    ver = version.removeprefix("v")
    section = section_body(text, ver)
    if not section:
        raise BuildError(f"The '## [{ver}]' section is empty — it is the release note, so write it.")
    return section


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
            f"The '## [{version}]' section is not release-note shaped:\n" + "\n".join(f"  - {e}" for e in errors)
        )


def check_unreleased(text: str) -> None:
    """Run the caps against `[Unreleased]`, so a section cannot go over between releases.

    Caps-only on purpose: no version list, no compare link, no install block — none of which exist
    for an unpinned section. `check_caps()` is reused unchanged, so what `./check` enforces and what
    the release refuses are the same rule rather than two copies of it.

    An empty `[Unreleased]` passes. `pin_release.py` opens a fresh empty one immediately after
    pinning the outgoing section, so treating empty as a failure would turn every release branch
    red on the gate that is supposed to protect it. Nothing to cap is not a cap violation, and
    `pin_release.py` already refuses to release an empty section.
    """
    check_caps(section_body(text, "Unreleased"), "Unreleased")


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

    # section_body() matches any literal label, so this is what still insists on a *pinned* version.
    # Without it `versions.index(ver)` below raises ValueError rather than something readable.
    versions = released_versions(text)
    if ver not in versions:
        raise BuildError(f"CHANGELOG has no '## [{ver}]' heading — pin the [Unreleased] section first.")

    section = extract_section(text, ver)
    check_caps(section, ver)

    parts = [section, installation_block(ver)]

    # The previous release is the next pinned heading below this one. A first release has none.
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
    parser.add_argument(
        "version",
        help="release version, e.g. 0.11.0 (leading v allowed), or 'Unreleased' to check the caps only",
    )
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

    text = changelog.read_text(encoding="utf-8")

    # One try, so both modes report a missing section as 1 and a cap violation as 2. Splitting them
    # is how the two drift into disagreeing about what the same failure means.
    try:
        # `./check` runs this mode on every commit. An unpinned section has no version to install
        # or compare against, so there is nothing to build — only the caps to enforce, early enough
        # that whoever wrote the entry is still the one who has to fix it.
        if args.version == "Unreleased":
            check_unreleased(text)
            print("[Unreleased] is release-note shaped.")
            return 0
        body = build(text, args.version)
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
