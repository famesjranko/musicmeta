#!/usr/bin/env python3
"""Fail unless every module's tests actually ran in *this* `./check`.

`org.gradle.caching=true`, and the cache directory is shared by every checkout on the machine. A
`Test` task's outputs — its JUnit XML included — are restored wholesale on an input-hash hit, so
`./check` can print a green line having executed no test at all: the reports on disk belong to
whichever tree first produced them, and can be days old. Gradle's keys cover declared inputs only,
so anything a test reads that Gradle cannot see — an environment variable, a file above the project
directory, a credential — is free to differ between the tree that stored the entry and the tree
that reuses it, and an inherited green says nothing about the second.

Two rules, and neither implies the other:

  1. every JUnit report under a module's `build/test-results/` carries a timestamp at or after the
     run's start, so a restored or left-over report is reported rather than read as evidence, and
  2. every module with test sources produced at least one report, so a test task that was skipped,
     filtered to nothing, or never wired into `check` is reported instead of passing by absence.

Modules are discovered from `src/test/`, not listed: a hand-written list stops covering the next
module silently, which is the same shape of failure this check exists to catch.

    python3 check_test_freshness.py --since EPOCH [--root PATH] [--exclude MODULE ...]

`--exclude` is for a module whose tests this invocation deliberately did not run — `./check
--skip-demo` leaves the demo canary to its own CI job, and those modules' reports are then
legitimately from an earlier run.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

# `schemaPin` writes here but is never part of `check`: it asks live upstreams and runs on a
# schedule, so its report is always older than the current run and is not evidence about this tree.
IGNORED_TASK_DIRS = frozenset({"schemaPin"})

# Directories that are not Gradle modules of this build. `.claude/` holds whole worktree copies of
# this repo, which would otherwise be discovered as modules of it.
PRUNED_DIRS = frozenset({".git", ".claude", "build", "node_modules"})


def find_test_modules(root: Path) -> list[Path]:
    """Given a repo root, return the module directories that have Kotlin or Java test sources."""
    modules = []
    for src_test in root.rglob("src/test"):
        if any(part in PRUNED_DIRS for part in src_test.relative_to(root).parts):
            continue
        if not src_test.is_dir():
            continue
        if any(src_test.rglob("*.kt")) or any(src_test.rglob("*.java")):
            modules.append(src_test.parent.parent)
    return sorted(set(modules))


def report_timestamp(xml: Path) -> datetime | None:
    """Return the run time a JUnit report claims, or None when it does not parse."""
    try:
        root = ElementTree.parse(xml).getroot()
    except ElementTree.ParseError:
        return None
    raw = root.get("timestamp")
    if not raw:
        return None
    try:
        stamp = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    # Gradle emits UTC with a `Z`; a naive stamp from another writer is read as local time, which is
    # the only reading that can be compared against a wall-clock start.
    return stamp if stamp.tzinfo else stamp.astimezone()


def run(root: Path, since: datetime, excluded: frozenset[str]) -> list[str]:
    """Return one message per module whose reports are missing or predate the run's start."""
    problems = []
    modules = find_test_modules(root)
    if not modules:
        return [f"no module under {root} has test sources — this check scanned nothing"]
    for module in modules:
        name = module.relative_to(root).as_posix()
        if name in excluded:
            continue
        results = module / "build" / "test-results"
        reports = [
            xml
            for task_dir in sorted(results.glob("*"))
            if task_dir.is_dir() and task_dir.name not in IGNORED_TASK_DIRS
            for xml in sorted(task_dir.glob("TEST-*.xml"))
        ]
        if not reports:
            problems.append(f"{name}: has test sources but produced no JUnit report in {results}")
            continue
        for xml in reports:
            stamp = report_timestamp(xml)
            if stamp is None:
                problems.append(f"{xml.relative_to(root)}: no readable timestamp")
            elif stamp < since:
                problems.append(
                    f"{xml.relative_to(root)}: ran at {stamp.isoformat()}, "
                    f"before this run started at {since.isoformat()} — a stale or restored result"
                )
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", type=int, required=True, help="run start, epoch seconds")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--exclude", action="append", default=[], metavar="MODULE")
    args = parser.parse_args(argv)

    since = datetime.fromtimestamp(args.since, tz=timezone.utc)
    problems = run(args.root.resolve(), since, frozenset(args.exclude))
    if problems:
        print("test results are not from this run:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print("every module's tests ran in this build")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
