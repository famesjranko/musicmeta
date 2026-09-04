#!/usr/bin/env python3
"""Self-check for check_test_freshness.py.

A gate nobody has watched fail is not a gate, so each way a tree can fail to earn its green is
proved here: a report older than the run, a module with test sources and no report at all, and a
report whose timestamp cannot be read. The two ways this check can scan nothing and still pass —
a tree with no test sources, and a module excluded by name — are proved too, because a check that
silently examines nothing reads exactly like a clean bill of health. Run with:
python3 test_check_test_freshness.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_test_freshness import find_test_modules, run  # noqa: E402

START = datetime(2026, 9, 5, 12, 0, 0, tzinfo=timezone.utc)


def report(stamp: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="a.B" tests="1" skipped="0" failures="0" errors="0" '
        f'timestamp="{stamp}" hostname="h" time="0.01"/>\n'
    )


class TestFreshnessTest(unittest.TestCase):
    def module(self, root: Path, name: str) -> Path:
        """Given a repo root, create a module with one Kotlin test source."""
        source = root / name / "src" / "test" / "kotlin"
        source.mkdir(parents=True)
        (source / "BTest.kt").write_text("class BTest\n")
        return root / name

    def results(self, module: Path, task: str, stamp: str) -> Path:
        """Place one JUnit report for a task, claiming the given run time."""
        task_dir = module / "build" / "test-results" / task
        task_dir.mkdir(parents=True, exist_ok=True)
        path = task_dir / "TEST-a.B.xml"
        path.write_text(report(stamp))
        return path

    def test_a_report_from_this_run_passes(self) -> None:
        # Given - a module whose only report was written after the run started
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(self.module(root, "core"), "test", "2026-09-05T12:00:30.000Z")
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - it reports nothing
            self.assertEqual([], problems)

    def test_a_report_predating_the_run_is_reported(self) -> None:
        # Given - a module whose report was restored from an earlier day
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(self.module(root, "core"), "test", "2026-09-04T16:09:41.576Z")
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - the stale report is named
            self.assertEqual(1, len(problems))
            self.assertIn("stale or restored", problems[0])

    def test_one_stale_report_among_fresh_ones_is_reported(self) -> None:
        # Given - a module whose two test tasks ran, one of them served from the cache
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module = self.module(root, "android")
            self.results(module, "testDebugUnitTest", "2026-09-05T12:00:30.000Z")
            self.results(module, "testReleaseUnitTest", "2026-09-01T09:00:00.000Z")
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - only the restored task is named
            self.assertEqual(1, len(problems))
            self.assertIn("testReleaseUnitTest", problems[0])

    def test_a_module_with_no_report_at_all_is_reported(self) -> None:
        # Given - a module with test sources whose test task produced nothing
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.module(root, "core")
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - the absence is reported rather than passing
            self.assertEqual(1, len(problems))
            self.assertIn("produced no JUnit report", problems[0])

    def test_an_unreadable_timestamp_is_reported(self) -> None:
        # Given - a module whose report carries no timestamp attribute
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module = self.module(root, "core")
            task_dir = module / "build" / "test-results" / "test"
            task_dir.mkdir(parents=True)
            (task_dir / "TEST-a.B.xml").write_text('<testsuite name="a.B"/>\n')
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - the unreadable report is named rather than trusted
            self.assertEqual(1, len(problems))
            self.assertIn("no readable timestamp", problems[0])

    def test_the_schema_pin_task_is_not_read_as_evidence(self) -> None:
        # Given - a module whose only stale report belongs to the scheduled schema pin
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module = self.module(root, "core")
            self.results(module, "test", "2026-09-05T12:00:30.000Z")
            self.results(module, "schemaPin", "2026-08-01T00:00:00.000Z")
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - the pin's own age is not a finding
            self.assertEqual([], problems)

    def test_an_excluded_module_is_not_required_to_have_run(self) -> None:
        # Given - a demo module left to its own CI job, holding a report from an earlier run
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(self.module(root, "core"), "test", "2026-09-05T12:00:30.000Z")
            self.results(self.module(root, "demo-web"), "test", "2026-09-01T09:00:00.000Z")
            # When - the check reads the tree with that module excluded
            problems = run(root, START, frozenset({"demo-web"}))
            # Then - only the module this run covered is judged
            self.assertEqual([], problems)

    def test_a_tree_with_no_test_sources_fails_rather_than_passing_empty(self) -> None:
        # Given - a tree in which no module has test sources
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "core" / "src" / "main").mkdir(parents=True)
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - scanning nothing is a failure, not a clean bill of health
            self.assertEqual(1, len(problems))
            self.assertIn("scanned nothing", problems[0])

    def test_a_nested_worktree_copy_is_not_discovered_as_a_module(self) -> None:
        # Given - a whole copy of the repo under .claude/, as an agent worktree leaves one
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.results(self.module(root, "core"), "test", "2026-09-05T12:00:30.000Z")
            self.module(root, ".claude/worktrees/wt/core")
            # When - modules are discovered
            modules = find_test_modules(root)
            # Then - only this build's module is one, so the copy cannot fail the run
            self.assertEqual([root / "core"], modules)
            self.assertEqual([], run(root, START, frozenset()))

    def test_a_report_written_a_second_before_the_start_is_reported(self) -> None:
        # Given - a report from just before the run began, which no re-run could have produced
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            just_before = (START - timedelta(seconds=1)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
            self.results(self.module(root, "core"), "test", just_before)
            # When - the check reads the tree
            problems = run(root, START, frozenset())
            # Then - the boundary is not rounded away
            self.assertEqual(1, len(problems))


if __name__ == "__main__":
    unittest.main()
