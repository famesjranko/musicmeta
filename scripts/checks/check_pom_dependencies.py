#!/usr/bin/env python3
"""Fail when musicmeta-core's published dependencies diverge from the committed baseline.

`apiCheck` reads the ABI, and a transitive is not in it: adding a dependency to core compiles,
passes every test, moves no `api/*.api` line, and then lands on every consumer's classpath at the
next release, where removing it is a break. The published POM is where that invariant actually
lives, so this reads the POM rather than `musicmeta-core/build.gradle.kts` — the build script sees
three declarations while the artifact a consumer resolves carries four, and it cannot see a
dependency contributed by a plugin, a `subprojects { }` block or a `configurations.all`.

Only `<dependencies>` is baselined. The rest of the POM moves on every version bump, which would
make the file noisy and train its readers to regenerate without looking.

Versions are part of the baseline. Gradle resolves the highest version in the graph, so raising one
here raises a floor every consumer inherits — the same class of consumer-visible move as adding a
dependency, and invisible to every other gate.

The POM is generated, not committed: `./gradlew :musicmeta-core:generatePomFileForMavenPublication`
writes it, and `./check` runs that task immediately before this script. An absent, unreadable or
dependency-less POM is a finding, never a pass: reporting clean on input that was never read is the
one failure this gate exists to prevent.

    python3 check_pom_dependencies.py [--root PATH] [--write]

`--write` regenerates the baseline from the POM and is what `make pom-dump` calls. It refuses when
the POM cannot be read, so a stale or missing build directory cannot silently empty the baseline.
"""

from __future__ import annotations

import argparse
import difflib
from pathlib import Path
from xml.etree import ElementTree

POM = "musicmeta-core/build/publications/maven/pom-default.xml"
BASELINE = "musicmeta-core/api/musicmeta-core.pom-dependencies"

HEADER = (
    "# musicmeta-core's published dependencies, one per line, as "
    "`groupId:artifactId:version` plus\n"
    "# every other element the POM's `<dependency>` carries. Generated from\n"
    f"# `{POM}`; regenerate with `make pom-dump` and\n"
    "# review the diff — it is the record of what a consumer inherits.\n"
)

# Rendered first and in this order because it is how a coordinate reads; anything else the POM
# starts emitting sorts after them rather than being dropped, so an unmodelled element moves the
# baseline instead of passing unseen.
ELEMENT_ORDER = ("scope", "optional", "classifier", "type")


def _tag(element: ElementTree.Element) -> str:
    """The element's local name, with the POM's default namespace stripped."""
    return element.tag.rpartition("}")[2]


def _render(element: ElementTree.Element) -> str:
    """An element's value, flattened, so a nested element cannot vanish from a line."""
    children = list(element)
    if children:
        return "{" + " ".join(f"{_tag(c)}={_render(c)}" for c in children) + "}"
    return (element.text or "").strip()


def dependency_line(dependency: ElementTree.Element) -> tuple[str, list[str]]:
    """One baseline line for a `<dependency>`, plus a finding for each coordinate part missing."""
    values = {_tag(child): _render(child) for child in dependency}
    findings = [
        f"::error file={POM}::a `<dependency>` has no `<{part}>`, so it has no coordinate. "
        "The POM's shape changed, or it was read from the wrong publication."
        for part in ("groupId", "artifactId", "version")
        if not values.get(part)
    ]
    coordinate = ":".join(values.pop(part, "") or "<missing>" for part in ("groupId", "artifactId", "version"))
    rest = sorted(
        values, key=lambda name: (ELEMENT_ORDER.index(name) if name in ELEMENT_ORDER else len(ELEMENT_ORDER), name)
    )
    return " ".join([coordinate, *(f"{name}={values[name]}" for name in rest)]), findings


def extract(root: Path) -> tuple[list[str], list[str]]:
    """The POM's dependency lines, sorted, and the findings that make them untrustworthy."""
    path = root / POM
    if not path.exists():
        return [], [
            f"::error file={POM}::no generated POM. Run "
            "`./gradlew :musicmeta-core:generatePomFileForMavenPublication` first — this check "
            "reads the published artifact, and reading nothing is not a clean run."
        ]
    try:
        project = ElementTree.parse(path).getroot()
    except ElementTree.ParseError as error:
        return [], [f"::error file={POM}::is not readable XML ({error}), so nothing was checked."]

    dependencies = next((child for child in project if _tag(child) == "dependencies"), None)
    if dependencies is None:
        return [], [
            f"::error file={POM}::has no `<dependencies>` element. That element is the whole gate, "
            "so its absence is a finding rather than a dependency count of zero."
        ]

    lines, findings = [], []
    for dependency in dependencies:
        if _tag(dependency) != "dependency":
            continue
        line, problems = dependency_line(dependency)
        lines.append(line)
        findings.extend(problems)
    if not lines:
        findings.append(
            f"::error file={POM}::declares no dependency at all. musicmeta-core resolves several, "
            "so an empty list means the wrong file was read, not that the floor moved."
        )
    return sorted(lines), findings


def baseline_lines(root: Path) -> tuple[list[str], list[str]]:
    """The committed baseline's dependency lines, and the findings that make them untrustworthy."""
    path = root / BASELINE
    if not path.exists():
        return [], [
            f"::error file={BASELINE}::no committed baseline, so there is nothing to compare the "
            "published POM against. Restore it, or create it with `make pom-dump`."
        ]
    lines = [
        stripped
        for line in path.read_text(encoding="utf-8").splitlines()
        if (stripped := line.strip()) and not stripped.startswith("#")
    ]
    if not lines:
        return [], [
            f"::error file={BASELINE}::holds no dependency line, so every comparison against it is "
            "vacuous. Regenerate it with `make pom-dump`."
        ]
    return sorted(lines), []


def run(root: Path) -> list[str]:
    """All findings for the tree at root, formatted for GitHub annotations."""
    published, findings = extract(root)
    committed, baseline_findings = baseline_lines(root)
    findings += baseline_findings
    if findings:
        return findings

    diff = [
        line
        for line in difflib.unified_diff(committed, published, lineterm="")
        if line[:1] in "+-" and line[1:2] not in "+-"
    ]
    if diff:
        findings.append(
            f"::error file={BASELINE}::musicmeta-core's published dependencies diverge from the "
            "baseline. Every line here reaches a consumer's classpath and cannot be withdrawn "
            "without a break; a version raises a floor Gradle applies to the whole graph. If the "
            "move is intended, run `make pom-dump` and review the diff:\n" + "\n".join(diff)
        )
    return findings


def write_baseline(root: Path) -> list[str]:
    """Rewrite the baseline from the generated POM, or report why it was left alone."""
    published, findings = extract(root)
    if findings:
        return findings
    path = root / BASELINE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(HEADER + "\n".join(published) + "\n", encoding="utf-8")
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--write", action="store_true", help="regenerate the baseline from the POM")
    args = parser.parse_args()
    root = args.root.resolve()
    findings = write_baseline(root) if args.write else run(root)
    for finding in findings:
        print(finding)
    if args.write and not findings:
        print(f"wrote {BASELINE}")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
