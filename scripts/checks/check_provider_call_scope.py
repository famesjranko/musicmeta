#!/usr/bin/env python3
"""Fail on a provider directory that never mentions `ProviderCallScope`.

`DefaultEnrichmentEngine` calls `EnrichmentProvider.enrich(request, type)` once per type, and
`EnrichmentCache` is keyed by type — when several types are served from one upstream document, only
the provider can tell. `ProviderCallScope` exists so a provider can memoize for the life of one call
(see its KDoc at `musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/ProviderCallScope.kt`),
and `docs/pitfalls.md` §12 explains why the memo must die with the call rather than outlive it. Five
of eleven providers missed it, and five of those were written after it already existed — a
documented rule a provider author never saw fail, because forgetting it is silent: the provider
still returns correct answers and just repeats the same upstream request once per type. This check
is the mechanism that makes it visible.

**The rule:** every `provider/<name>/` directory that declares a `*Provider.kt` must either mention
`ProviderCallScope` somewhere in that directory, or be named in `ALLOWLIST` below with a one-line
reason a reviewer reads. The allowlist is the design, not an escape hatch — a provider whose types
provably hit disjoint upstream endpoints has nothing to memoize and says so in one line.

**What this cannot prove.** A substring match only shows a directory *mentions* `ProviderCallScope`
— not that it memoizes the right call, keys it correctly, or that the memo is ever reached on the
path that needs it. The semantic guard is a per-provider request-count test — the shape the affected
providers carry — and `ProviderMemoLifetimeTest` engine-side. A green run here is not evidence the
memoization works; it only shows nobody forgot the mechanism exists.

Plain substring matching over the directory's text, no Kotlin front end — matching
`check_conventions.py`.

    python3 check_provider_call_scope.py [--root PATH]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

PROVIDER_ROOT = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider"
MENTION = "ProviderCallScope"

# A directory whose types provably hit disjoint upstream endpoints has nothing to memoize, and this
# is where that argument is written down for a reviewer to read. Starts empty: after the five
# missing providers were fixed, all eleven provider directories mention `ProviderCallScope` directly,
# so nothing needs an exemption today. This mapping exists for the twelfth provider, not the current
# eleven — an untested empty branch is how an escape hatch turns out to be broken the first time
# someone legitimately needs it.
ALLOWLIST: dict[str, str] = {}

NO_PROVIDERS_FINDING = (
    f"::error::no directory with a `*Provider.kt` found under `{PROVIDER_ROOT}`, so this check "
    "scanned nothing. Fix the path above, or the check is silently passing on an empty tree."
)


def fix_message(name: str) -> str:
    return (
        f"no file under provider/{name}/ mentions `ProviderCallScope`. Read its KDoc "
        "(musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/engine/ProviderCallScope.kt) and "
        "`docs/pitfalls.md` §12: if two of this provider's types can be served from one upstream "
        "document, memoize it in a `ProviderCallScope` slot so the per-type fan-out does not repeat "
        f"the request. If this provider's types provably hit disjoint endpoints, add "
        f'`"{name}": "<one-line reason>"` to ALLOWLIST in check_provider_call_scope.py instead.'
    )


def provider_directories(root: Path) -> list[Path]:
    """`provider/<name>/` directories that declare at least one `*Provider.kt`, sorted by name.

    Direct children only: every provider under `provider/` is a flat, single-level directory, and a
    subdirectory of one (there are none today) is still read by `mentions_scope` below.
    """
    provider_root = root / PROVIDER_ROOT
    if not provider_root.is_dir():
        return []
    return sorted(
        (
            directory
            for directory in provider_root.iterdir()
            if directory.is_dir() and any(directory.glob("*Provider.kt"))
        ),
        key=lambda directory: directory.name,
    )


def mentions_scope(directory: Path) -> bool:
    """Whether any file in `directory` mentions `ProviderCallScope`, textually."""
    return any(MENTION in path.read_text(encoding="utf-8") for path in sorted(directory.rglob("*.kt")))


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def run(root: Path, allowlist: dict[str, str] | None = None) -> list[str]:
    """All findings for the tree at root.

    `allowlist` defaults to the module-level `ALLOWLIST`; a test passes its own so it can prove the
    allowlisted path without mutating shared state.
    """
    allow = ALLOWLIST if allowlist is None else allowlist
    directories = provider_directories(root)
    if not directories:
        return [NO_PROVIDERS_FINDING]

    findings = []
    for directory in directories:
        name = directory.name
        if name in allow:
            continue
        if mentions_scope(directory):
            continue
        # The first `*Provider.kt`, sorted, so the finding always lands on the same file across
        # runs — a directory with more than one (deezer) still reports at a stable location.
        provider_file = sorted(directory.glob("*Provider.kt"))[0]
        rel = provider_file.relative_to(root).as_posix()
        findings.append(error(rel, 1, fix_message(name)))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Fail on a provider directory that never mentions ProviderCallScope.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} provider-call-scope violation(s).", file=sys.stderr)
        return 2

    print(f"ProviderCallScope discoverability clean across {len(provider_directories(root))} provider directories.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
