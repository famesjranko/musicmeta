#!/usr/bin/env python3
"""Fail on a dependency added to `musicmeta-core` that nobody argued for.

`ARCHITECTURE.md` states the invariant outright: **core is dependency-minimal JVM** — coroutines,
`org.json`, and kotlinx-serialization, nothing else. It is what lets a server or desktop consumer
take the engine without an Android artifact or a wire library, and it was prose until this check.

Adding one is silent in a way most changes are not. It compiles, every test passes, `apiCheck` sees
nothing — a transitive dependency is not part of the ABI — and the cost lands on every consumer's
classpath at the next release, where removing it is a break.

**The rule:** every non-test configuration in `musicmeta-core/build.gradle.kts` names a catalog
accessor listed in `ALLOWED` below, with the reason a reviewer reads. Adding a dependency means
adding the row and defending it, which is the whole mechanism.

**Scope is core only**, and that is deliberate rather than unfinished: `musicmeta-okhttp` exists to
bring OkHttp and `musicmeta-android` to bring Room, Hilt and WorkManager, so the same rule there
would fail the modules for doing their job. `MODULE` is the one place to change if that judgement
ever does.

**What this cannot prove.** It reads the build script, not the resolved graph — a transitive
arriving under an allowed direct dependency is invisible here, and so is a version bump. The POM a
consumer actually gets is what `./gradlew :musicmeta-core:generatePomFileForMavenPublication`
writes, and nothing compares against it.

    python3 check_core_dependencies.py [--root PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MODULE = "musicmeta-core/build.gradle.kts"

# Configurations that reach a consumer. `api` puts the dependency on their compile classpath and
# makes its version part of the published contract; `implementation` and `runtimeOnly` still ship
# it transitively at runtime. Test and testFixtures configurations reach nobody and are not listed.
PUBLISHED_CONFIGURATIONS = ("api", "implementation", "compileOnly", "runtimeOnly")

# Every dependency core is allowed to declare, and why. A new row is the argument; the check only
# makes someone write it down.
ALLOWED = {
    "libs.kotlinx.coroutines.core": "the concurrency primitive the whole engine is built on",
    "libs.json": "JSON parsing, kept off the consumer's compile classpath as `implementation`",
    "libs.kotlinx.serialization.json": (
        "the cache payload format — `api`, so its version is part of the published contract"
    ),
}

DEPENDENCY = re.compile(r"^\s*(\w+)\s*\(\s*([\w.]+)\s*\)")

NO_BLOCK_FINDING = (
    f"::error file={MODULE}::no `dependencies {{ }}` block found, so this check read nothing. "
    "Fix the parse, or the rule is silently passing."
)


def dependencies_block(text: str) -> str | None:
    """The body of the top-level `dependencies { }` block, or None if there is not one.

    Brace-counted rather than read to the next `}`: a dependency declared with a trailing lambda
    would otherwise truncate the block and hide every line after it.
    """
    match = re.search(r"^dependencies\s*\{", text, re.MULTILINE)
    if match is None:
        return None
    depth, start = 0, match.end() - 1
    for index in range(start, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1 : index]
    return None


def declared(block: str) -> list[tuple[int, str, str]]:
    """Every (line offset, configuration, accessor) the block declares."""
    found = []
    for offset, line in enumerate(block.split("\n")):
        match = DEPENDENCY.match(line)
        if match is not None:
            found.append((offset, match.group(1), match.group(2)))
    return found


def fix(configuration: str, accessor: str) -> str:
    return (
        f"`{configuration}({accessor})` is not on core's allowlist. Core is dependency-minimal JVM "
        "(`ARCHITECTURE.md`) — a dependency here reaches every consumer transitively and cannot be "
        "removed without a break. Move it to an adapter module, or add it to `ALLOWED` in "
        "`scripts/checks/check_core_dependencies.py` with the reason."
    )


def run(root: Path) -> list[str]:
    path = root / MODULE
    if not path.is_file():
        return [f"::error::`{MODULE}` not found, so core's dependency rule checked nothing."]

    text = path.read_text(encoding="utf-8")
    block = dependencies_block(text)
    if block is None:
        return [NO_BLOCK_FINDING]

    # The line the block starts on, so a finding points at the declaration rather than the block.
    block_start = text[: text.index(block)].count("\n") + 1

    findings = []
    for offset, configuration, accessor in declared(block):
        if configuration not in PUBLISHED_CONFIGURATIONS or accessor in ALLOWED:
            continue
        findings.append(f"::error file={MODULE},line={block_start + offset}::{fix(configuration, accessor)}")
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Keep musicmeta-core dependency-minimal.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    findings = run(root)

    for finding in findings:
        print(finding, file=sys.stderr)
    if findings:
        print(f"\n{len(findings)} core dependency violation(s).", file=sys.stderr)
        return 2

    block = dependencies_block((root / MODULE).read_text(encoding="utf-8")) or ""
    published = [d for d in declared(block) if d[1] in PUBLISHED_CONFIGURATIONS]
    # The count is the only thing separating a full read from a parse that matched nothing.
    print(f"Core declares {len(published)} published dependencies, all allowlisted.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
