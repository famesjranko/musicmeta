#!/usr/bin/env python3
"""Fail on an upstream's vocabulary leaking into the published surface unqualified.

Eleven upstreams name the same three things differently: MusicBrainz calls a track a *recording*
and an album a *release-group*, Discogs calls an album a *master*, iTunes calls it a *collection*.
`docs/glossary.md` is the map. A mapper's whole job is to cross it, and a public name that crosses
it in the wrong direction publishes an upstream's schema under this library's compatibility
promise — where it cannot be renamed without a break.

**The rule:** a banned stem may appear in a public identifier only when a provider's name is
attached, in the identifier itself or in its enclosing type. `getMusicBrainzReleaseGroupId` and
`MusicBrainzEntityType.RECORDING` both say whose word it is and are legal; a bare `recordingId` on
a public type does not and is not.

Read from the committed `api/*.api` rather than the sources, matching `check_conventions.py`: the
dump is the consequence, so it catches a public nested type and a top-level `fun`'s `…Kt` facade
just as well as a data-class property. The trade-off is the same one — a stale dump reports clean
here, and `apiCheck` is what fails in that case.

**What this cannot prove.** It reads names, not meaning. A public `albumId` that actually holds a
release id passes; so does a name that is merely wrong rather than borrowed. Both stay review's job
(`docs/agents/review-checklist.md`).

`collection` is deliberately not banned. `api/*.api` lines carry erased JVM descriptors, so
`Ljava/util/Collection;` appears on a large fraction of them — banning the stem would report a
finding on nearly every line and the rule would be turned off within a day.

    python3 check_public_vocabulary.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Kept in step with `check_conventions.py`, which excludes the same tree for the same reason: a
# worktree is another branch's checkout, and its surface is that branch's PR to answer for.
AGENT_WORKTREES = "/.claude/worktrees/"

# The upstream words for concepts this library already names. `docs/glossary.md` holds the mapping
# and is the file to change first — adding a stem here without a row there leaves the fix message
# pointing at nothing.
BANNED_STEMS = {
    "recording": "track",
    "releasegroup": "album",
    "master": "album",
}

# The providers whose name qualifies a borrowed word. Lowercased, matched as a substring of the
# identifier and of its enclosing type, so `MusicBrainzEntityType.RECORDING` is covered by the
# type's name rather than the member's.
PROVIDER_QUALIFIERS = (
    "musicbrainz",
    "listenbrainz",
    "discogs",
    "itunes",
    "deezer",
    "lastfm",
    "lrclib",
    "wikidata",
    "wikipedia",
    "fanart",
    "coverart",
)

# A borrowed word that is neither qualified nor renameable, with the reason a reviewer reads.
# Empty today: all four borrowed words in the surface carry a provider's name. It exists for the
# case that has not happened yet — an untested empty branch is how an escape hatch turns out to be
# broken the first time someone legitimately needs it.
ALLOWLIST: dict[str, str] = {}

NO_API_DUMPS_FINDING = (
    "::error::no `api/*.api` found, so the vocabulary rule checked nothing. Run "
    "`./gradlew apiDump`, or fix `api_dumps()` if the baselines moved."
)

# A member's name is the token after `fun`/`field`; a declaration's is its binary name, taken as
# the first `/`-bearing token exactly as `check_conventions.py` does — a supertype list means the
# last such token is `Enum`, not the type being declared.
MEMBER = re.compile(r"^\s+(?:public|protected)\s+.*?\b(?:fun|field|val|var)\s+([\w$-]+)")


def api_dumps(root: Path) -> list[Path]:
    """The committed public-ABI baselines, one per published module."""
    return sorted(
        path
        for path in root.rglob("api/*.api")
        if "/build/" not in path.as_posix() and AGENT_WORKTREES not in path.as_posix()
    )


def is_qualified(*names: str) -> bool:
    """True when any of these names carries a provider's name, so the borrowed word is attributed."""
    lowered = "".join(name.lower() for name in names)
    return any(qualifier in lowered for qualifier in PROVIDER_QUALIFIERS)


def offending_stem(identifier: str, enclosing: str) -> str | None:
    """The banned stem this identifier borrows unqualified, or None if it borrows nothing."""
    flattened = identifier.lower().replace("_", "").replace("-", "")
    for stem in BANNED_STEMS:
        if stem in flattened and not is_qualified(identifier, enclosing):
            return stem
    return None


def scan(text: str) -> list[tuple[int, str, str]]:
    """Every (line number, identifier, stem) an `api/*.api` body borrows unqualified.

    Tracks the enclosing declaration as it goes: a member inherits its owner's attribution, which
    is what makes `MusicBrainzEntityType.RECORDING` legal without naming the provider twice.
    """
    findings = []
    enclosing = ""
    for lineno, line in enumerate(text.split("\n"), start=1):
        if line[:1] not in (" ", "\t") and line.strip():
            binary_name = next((token for token in line.split() if "/" in token), None)
            enclosing = binary_name.rsplit("/", 1)[-1] if binary_name else ""
            name = enclosing
        else:
            match = MEMBER.match(line)
            if match is None:
                continue
            name = match.group(1)
        if not name or name in ALLOWLIST:
            continue
        stem = offending_stem(name, enclosing if name != enclosing else "")
        if stem is not None:
            findings.append((lineno, name, stem))
    return findings


def fix(name: str, stem: str) -> str:
    return (
        f"`{name}` borrows `{stem}` — this library's word is `{BANNED_STEMS[stem]}` "
        f"(`docs/glossary.md`). Rename it, or attach the provider's name if it really is that "
        f"upstream's entity (`musicBrainz{stem.capitalize()}Id`). Re-run `./gradlew apiDump` after."
    )


def run(root: Path) -> list[str]:
    findings = []
    dumps = api_dumps(root)
    # An empty result reads exactly like a clean one, and this scan is the only reader of the
    # baselines for this rule — the same silence `check_conventions.py` guards against.
    if not dumps:
        findings.append(NO_API_DUMPS_FINDING)

    for path in dumps:
        rel = path.relative_to(root).as_posix()
        for lineno, name, stem in scan(path.read_text(encoding="utf-8")):
            findings.append(f"::error file={rel},line={lineno}::{fix(name, stem)}")
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Keep upstream vocabulary out of the public surface.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} vocabulary violation(s).", file=sys.stderr)
        return 2

    # The count is the only thing separating a full scan from one that read nothing.
    print(f"Public vocabulary clean across {len(api_dumps(root))} api dumps.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
