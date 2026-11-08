#!/usr/bin/env python3
"""Check command wiring; the real cache/bytecode replay is recorded with the probe reports."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


class CheckCompilationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for relative in ("check", "scripts/bootstrap.sh", "scripts/toolchain.sh", "scripts/demo-canary.sh"):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(REPO / relative, target)
        for consumer in ("demo-cli", "demo-web", "docs-samples", "docs-samples-android"):
            (self.root / "consumers" / consumer).mkdir(parents=True)
        pins = (REPO / "scripts/bootstrap.sh").read_text()
        for name, pin in (("ruff", "RUFF_VERSION"), ("mypy", "MYPY_VERSION"), ("shellcheck", "SHELLCHECK_VERSION")):
            match = re.search(rf'^{pin}="([^"]+)"', pins, re.M)
            assert match
            self.stub(name, f'#!/bin/sh\necho "{name} {match[1]}"\n')
        self.stub("node", "#!/bin/sh\necho v22.0.0\n")
        self.stub("java", "#!/bin/sh\necho 'java.specification.version = 17' >&2\n")
        # These gates are independent of Gradle routing, and must not recursively run this test.
        self.stub("python3", "#!/bin/sh\nexit 0\n")
        self.stub("git", "#!/bin/sh\nexit 0\n")
        self.stub("npm", "#!/bin/sh\nexit 0\n")
        self.calls = self.root / "calls.jsonl"
        self.stub(
            "gradlew",
            f"""#!{sys.executable}
import json, os, sys
from pathlib import Path
with Path({str(self.calls)!r}).open('a') as log:
    log.write(json.dumps([os.getcwd(), sys.argv[1:]]) + '\\n')
if 'clean' in sys.argv and os.environ.get('MUSICMETA_TEST_FAIL_CLEAN'):
    sys.exit(42)
""",
            self.root,
        )
        (self.root / "sdk").mkdir()
        self.env = dict(os.environ, PATH=f"{self.bin}{os.pathsep}{os.defpath}", ANDROID_HOME=str(self.root / "sdk"))
        self.env.pop("JAVA_HOME", None)
        self.env.pop("MUSICMETA_TEST_FAIL_CLEAN", None)

    def stub(self, name: str, body: str, directory: Path | None = None) -> None:
        path = (directory or self.bin) / name
        path.write_text(body)
        path.chmod(0o755)

    def check(self, *args: str) -> tuple[subprocess.CompletedProcess[str], list]:
        result = subprocess.run(["bash", str(self.root / "check"), *args], env=self.env, capture_output=True, text=True)
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        return result, calls

    def assert_fresh_compilation(self, calls: list) -> None:
        cleaned: set[str] = set()
        for directory, args in calls:
            if "clean" in args:
                cleaned.add(directory)
                targets = [arg for arg in args if not arg.startswith("-")]
                self.assertEqual("clean", targets[0], args)
            if any("compile" in arg.lower() or arg in ("build", "detektMain") for arg in args):
                self.assertIn(directory, cleaned, f"compile before clean: {directory} {args}")
                self.assertIn(
                    "-Pmusicmeta.uncachedCompilation", args, f"compile can restore stale bytecode: {directory} {args}"
                )

    def test_full_check_cleans_and_bypasses_cache_in_every_build(self) -> None:
        # Given - all five Gradle builds can contain stale output or cached bytecode
        # When - the full check runs with Gradle recording its invocations
        result, calls = self.check()

        # Then - every compilation is preceded by clean and cannot restore cached bytecode
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(5, len({directory for directory, _ in calls}))
        self.assert_fresh_compilation(calls)

    def test_commit_check_bypasses_cache_without_cleaning(self) -> None:
        # Given - the hook retains warm output without sharing compiled entries
        # When - the commit check compiles that snapshot
        result, calls = self.check("--commit")

        # Then - it bypasses the build cache and preserves incremental output
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(any("clean" in args for _, args in calls))
        compilation = [args for _, args in calls if any("compile" in arg.lower() for arg in args)]
        self.assertTrue(compilation)
        for args in compilation:
            self.assertIn("--no-build-cache", args)

    def test_clean_failure_stops_verification_before_compilation(self) -> None:
        # Given - Gradle cannot remove the previous tree's outputs
        self.env["MUSICMETA_TEST_FAIL_CLEAN"] = "1"

        # When - verification tries to prepare fresh output
        result, calls = self.check()

        # Then - it fails without starting any compilation
        self.assertEqual(42, result.returncode, result.stdout + result.stderr)
        self.assertFalse(any("build" in args or "detektMain" in args for _, args in calls))

    def test_fast_check_keeps_the_edit_loop_incremental(self) -> None:
        # Given - the fast check runs no compilation
        # When - the edit loop check runs
        result, calls = self.check("--fast")

        # Then - it only checks Kotlin formatting and does not clean outputs
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([[str(self.root), ["ktlintCheck", "--quiet"]]], calls)


if __name__ == "__main__":
    unittest.main()
