#!/usr/bin/env python3
"""Fail on a provider whose upstream routes the daily drift watch does not pin.

The schema pin asks each upstream for one route a day and reports a field that has moved. It can
only report a route somebody declared: a provider with no target list is not reported as unwatched,
it is simply absent from the run, and the job stays green while that provider's fields move under
it. The twelfth provider is the one this exists for — the eleven here today were pinned by hand.

**The rule, in two halves.** Every `provider/<name>/` directory that declares a `*Provider.kt` must

  1. declare a target list — a `SCHEMA_PIN_TARGETS` value, or a `schemaPinTargets(` function where
     the routes need a credential the list must not hold as a constant — somewhere in the
     directory, and
  2. have that list read in `SchemaPinTargets.kt` — as `<Api>.SCHEMA_PIN_TARGETS` or
     `<Api>.schemaPinTargets(`, outside the import lines — which is the sum the run actually walks.

Both halves are needed and neither implies the other: a target list nothing registers is dead code
that reads as coverage, and a registry entry is what turns a declaration into a request. A provider
that genuinely has no route worth pinning goes in `ALLOWLIST` below with a one-line reason a
reviewer reads, which is the design and not an escape hatch.

One pinned route satisfies this, which is the point: it is the provider-level half, and asks only
that the mechanism was not forgotten. `check_route_pin_coverage.py` asks the route-level question of
the same tree, and neither answers the other's — a provider can pin every route it calls and still
leave the list unregistered, which is invisible there and reported here.

**What this cannot prove.** Substring matching only shows a target list exists and is named — not
that its paths mirror what the mapper reads, that its URL comes from the api client rather than
being hand-built, or that the route is one a consumer's answer depends on. Those are review's, and
`SchemaPinVerdictTest` proves only that the verdicts themselves fire. A green run here means nobody
forgot the mechanism exists.

Plain substring matching over the directory's text, no Kotlin front end — matching
`check_provider_call_scope.py`.

    python3 check_schema_pin_coverage.py [--root PATH]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

PROVIDER_ROOT = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider"
REGISTRY = "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/drift/SchemaPinTargets.kt"

# Either spelling counts: a keyless provider's routes are a constant list, and a keyed provider's
# are a function so the credential is passed in per run rather than captured into a static.
DECLARATIONS = ("SCHEMA_PIN_TARGETS", "schemaPinTargets(")

# A provider with no route worth pinning writes the argument here, for a reviewer to disagree with.
# Empty today: all eleven providers serve at least one route whose fields an answer depends on.
# It exists for the twelfth provider, not the current eleven — an untested empty branch is how an
# escape hatch turns out to be broken the first time somebody legitimately needs it.
ALLOWLIST: dict[str, str] = {}

NO_PROVIDERS_FINDING = (
    f"::error::no directory with a `*Provider.kt` found under `{PROVIDER_ROOT}`, so this check "
    "scanned nothing. Fix the path above, or the check is silently passing on an empty tree."
)

NO_REGISTRY_FINDING = (
    f"::error::`{REGISTRY}` is missing, so no provider can be registered and this check scanned "
    "nothing. Restore it, or the schema pin walks an empty target list."
)


def undeclared_message(name: str) -> str:
    return (
        f"no file under provider/{name}/ declares a schema-pin target list. Add a "
        f"`SCHEMA_PIN_TARGETS` value beside the parse it mirrors — or a `schemaPinTargets(key)` "
        f"function if the route needs a credential — naming the URL the api client builds and the "
        f"JSON paths this provider's mapper reads. Without one the daily drift watch never asks "
        f"this provider anything, and stays green while its fields move. If no route here is worth "
        f'pinning, add `"{name}": "<one-line reason>"` to ALLOWLIST in '
        f"check_schema_pin_coverage.py instead."
    )


def unregistered_message(name: str) -> str:
    return (
        f"provider/{name}/ declares a schema-pin target list that `{REGISTRY}` never reads, so the "
        f"daily drift watch does not walk it. Add its `SCHEMA_PIN_TARGETS` (or "
        f"`schemaPinTargets(key)`) term to `allSchemaPinTargets` — an import on its own resolves "
        f"the name and makes no request."
    )


def provider_directories(root: Path) -> list[Path]:
    """`provider/<name>/` directories that declare at least one `*Provider.kt`, sorted by name.

    Direct children only: every provider under `provider/` is a flat, single-level directory.
    """
    base = root / PROVIDER_ROOT
    if not base.is_dir():
        return []
    return sorted(
        directory for directory in base.iterdir() if directory.is_dir() and any(directory.glob("*Provider.kt"))
    )


def declares_targets(directory: Path) -> bool:
    """Whether any file in `directory` declares a target list, textually."""
    return any(
        any(marker in path.read_text(encoding="utf-8") for marker in DECLARATIONS)
        for path in sorted(directory.rglob("*.kt"))
    )


def error(rel: str, lineno: int, message: str) -> str:
    return f"::error file={rel},line={lineno}::{message}"


def anchor(directory: Path, root: Path) -> str:
    """The file a finding lands on: the first `*Provider.kt`, sorted, so the location is stable."""
    return sorted(directory.glob("*Provider.kt"))[0].relative_to(root).as_posix()


def run(root: Path, *, allowlist: dict[str, str] | None = None) -> list[str]:
    """All findings for the tree at root.

    `allowlist` defaults to the module-level `ALLOWLIST`; a test passes its own so it can prove the
    exemption path works while the real allowlist is empty.
    """
    allow = ALLOWLIST if allowlist is None else allowlist
    directories = provider_directories(root)
    if not directories:
        return [NO_PROVIDERS_FINDING]

    registry = root / REGISTRY
    if not registry.is_file():
        return [NO_REGISTRY_FINDING]
    registry_body = body_of(registry)

    findings: list[str] = []
    for directory in directories:
        name = directory.name
        if name in allow:
            continue
        rel = anchor(directory, root)
        if not declares_targets(directory):
            findings.append(error(rel, 1, undeclared_message(name)))
        elif not registry_reads(registry_body, directory):
            findings.append(error(rel, 1, unregistered_message(name)))
    return findings


def body_of(registry: Path) -> str:
    """The registry's text with its `import` lines dropped.

    The import is what makes the class name resolve, not what makes a request: a provider whose
    list is imported and never added to the sum is imported and never asked. Reading the whole file
    would let the import alone satisfy the check, which is the same silence it exists to catch.
    """
    return "\n".join(
        line for line in registry.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("import ")
    )


def registry_reads(registry_body: str, directory: Path) -> bool:
    """Whether `registry_body` actually reads a target list off this provider's `*Api` class.

    Matched as `<Api>.SCHEMA_PIN_TARGETS` or `<Api>.schemaPinTargets(` — the term that contributes
    to the sum the run walks.
    """
    return any(
        f"{api.stem}.{marker}" in registry_body for api in sorted(directory.glob("*Api.kt")) for marker in DECLARATIONS
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail on a provider whose upstream routes the drift watch does not pin.",
    )
    parser.add_argument("--root", default=".", type=Path, help="repository root (default: .)")
    args = parser.parse_args(argv)

    findings = run(args.root)
    for finding in findings:
        print(finding)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
