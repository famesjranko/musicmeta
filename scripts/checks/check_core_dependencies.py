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

Anything the parse does not recognise as a catalog accessor is reported, not skipped. A raw
`"group:name:version"` coordinate is the form a paste from a README takes, and skipping it would
leave the one route this gate exists to close wide open while still printing a clean count.

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

# A configuration name followed by its opening paren. Deliberately not a pattern for `libs.x`: a
# declaration this script cannot recognise has to reach `run()` as an argument that fails the
# allowlist, because a line skipped for failing to parse is a dependency added in silence.
CALL = re.compile(r"^\s*(\w+)\s*\(")

# The one argument shape the allowlist can name. Anything else is reported and sent to the catalog.
ACCESSOR = re.compile(r"^[\w.]+$")

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


def argument(line: str, open_index: int) -> str:
    """The text between this opening paren and its match, or the rest of the line if unbalanced.

    Paren-counted for the same reason the block is brace-counted: reading to the first `)` would
    cut `platform(libs.some.bom)` down to `platform(libs.some.bom`, and a truncated argument is a
    finding quoting something the build script does not say.
    """
    depth = 0
    for index in range(open_index, len(line)):
        if line[index] == "(":
            depth += 1
        elif line[index] == ")":
            depth -= 1
            if depth == 0:
                return line[open_index + 1 : index].strip()
    return line[open_index + 1 :].strip()


def declared(block: str) -> list[tuple[int, str, str]]:
    """Every (line offset, configuration, argument) the block declares.

    The argument is captured as written rather than parsed into a coordinate. `implementation(
    "group:name:version")` and a named-argument call are both legitimate Gradle and neither is a
    catalog accessor, so both have to arrive here and fail the allowlist rather than be skipped.
    """
    found = []
    for offset, line in enumerate(block.split("\n")):
        match = CALL.match(line)
        if match is not None:
            found.append((offset, match.group(1), argument(line, match.end() - 1)))
    return found


def fix(configuration: str, declaration: str) -> str:
    """The finding text, which differs by whether the allowlist can even name this declaration."""
    core_is_minimal = (
        "Core is dependency-minimal JVM (`ARCHITECTURE.md`) — a dependency here reaches every "
        "consumer transitively and cannot be removed without a break. Move it to an adapter module"
    )
    if declaration.startswith("project("):
        return (
            f"`{configuration}({declaration})` depends on another module of this repo. Core is the "
            "bottom of the stack (`ARCHITECTURE.md`) — the adapters depend on core, never the "
            "reverse. Move the code that needs this into the adapter."
        )
    if ACCESSOR.match(declaration):
        return (
            f"`{configuration}({declaration})` is not on core's allowlist. {core_is_minimal}, or "
            "add it to `ALLOWED` in `scripts/checks/check_core_dependencies.py` with the reason."
        )
    return (
        f"`{configuration}({declaration})` does not name a version-catalog accessor, so core's "
        f"allowlist cannot name it either. {core_is_minimal}, or declare it in "
        "`gradle/libs.versions.toml` and add the `libs.` accessor to `ALLOWED` in "
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
    for offset, configuration, declaration in declared(block):
        if configuration not in PUBLISHED_CONFIGURATIONS:
            continue
        if ACCESSOR.match(declaration) and declaration in ALLOWED:
            continue
        findings.append(f"::error file={MODULE},line={block_start + offset}::{fix(configuration, declaration)}")
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
