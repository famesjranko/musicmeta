#!/usr/bin/env python3
"""Self-tests for the JDK and Android SDK gates in `./check`.

Both gates are shell, so they are proved by running a copy of `./check` in a scratch tree with a
`PATH` holding only stubs. The stubs for ruff, mypy, shellcheck and node report their pinned
versions and then fail, so a run that gets past the toolchain gates stops at the first real step
instead of running the repository's checks. A stub `java` prints what a real one prints for
`-XshowSettings:properties -version`, which is all the gate reads.
"""

import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
# What a run that cleared both gates prints next: the first step header after the gates.
FIRST_STEP = "python format + lint (ruff)"
# A `java` argument that installs no java at all, as distinct from None, a java that fails.
NO_JAVA = "no java"


def java_stub(spec: str | None) -> str:
    """A `java` that reports `spec` as its specification version, or fails as a macOS shim does."""
    if spec is None:
        return "#!/bin/sh\nexit 1\n"
    return f'#!/bin/sh\necho "    java.specification.version = {spec}" >&2\necho "openjdk version \\"x\\"" >&2\n'


class ToolchainGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.base = Path(self._tmp.name)
        self.root = self.base / "repo"
        (self.root / "scripts").mkdir(parents=True)
        shutil.copy(REPO / "check", self.root / "check")
        for script in (REPO / "scripts").glob("*.sh"):
            shutil.copy(script, self.root / "scripts" / script.name)
        self.home = self.base / "home"
        self.home.mkdir()
        self.bin = self.base / "bin"
        self.bin.mkdir()
        for tool in ("bash", "sed", "grep", "dirname", "date", "cat", "tail"):
            found = shutil.which(tool)
            assert found, tool
            (self.bin / tool).symlink_to(found)
        bootstrap = (REPO / "scripts" / "bootstrap.sh").read_text(encoding="utf-8")
        pins = (("ruff", "RUFF_VERSION"), ("mypy", "MYPY_VERSION"), ("shellcheck", "SHELLCHECK_VERSION"))
        for tool, var in pins:
            match = re.search(rf'^{var}="([^"]*)"$', bootstrap, re.M)
            assert match, var
            version = match.group(1)
            report = f'[ "$1" = --version ] && {{ echo "{tool} {version}"; exit 0; }}'
            self.stub(tool, f"#!/bin/sh\n{report}\nexit 1\n")
        self.stub("node", "#!/bin/sh\necho v22.0.0\n")

    def stub(self, name: str, body: str, directory: Path | None = None) -> None:
        path = (directory or self.bin) / name
        path.write_text(body, encoding="utf-8")
        path.chmod(0o755)

    def run_check(
        self,
        *args: str,
        java: str | None = NO_JAVA,
        java_home: bool = False,
        android_home: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        """`java` is a spec version, None for a broken java, or NO_JAVA for none at all.

        With `java_home` the stub lives under JAVA_HOME; without a stub it names a missing JDK.
        """
        env = {"PATH": str(self.bin), "HOME": str(self.home)}
        if java != NO_JAVA and java_home:
            jdk_bin = self.base / "jdk" / "bin"
            jdk_bin.mkdir(parents=True, exist_ok=True)
            self.stub("java", java_stub(java), jdk_bin)
            env["JAVA_HOME"] = str(jdk_bin.parent)
        elif java != NO_JAVA:
            self.stub("java", java_stub(java))
        elif java_home:
            env["JAVA_HOME"] = str(self.base / "no-such-jdk")
        if android_home is not None:
            env["ANDROID_HOME"] = str(android_home)
        return subprocess.run(
            [str(self.bin / "bash"), str(self.root / "check"), *args],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def sdk(self) -> Path:
        path = self.base / "sdk"
        path.mkdir(exist_ok=True)
        return path

    # --- JDK: every mode, because even --fast runs ktlint through Gradle ---

    def assert_jdk_refused(self, result: subprocess.CompletedProcess[str], *needles: str) -> None:
        self.assertEqual(127, result.returncode, result.stderr)
        self.assertIn("java(", result.stderr)
        self.assertIn("17", result.stderr)
        for needle in needles:
            self.assertIn(needle, result.stderr)
        self.assertNotIn(FIRST_STEP, result.stdout)

    def test_no_java_is_refused_naming_the_required_version(self) -> None:
        # Given a machine with no java on PATH and no JAVA_HOME
        # When ./check runs
        result = self.run_check(android_home=self.sdk())
        # Then it exits 127 before any step and names java 17
        self.assert_jdk_refused(result, "absent")

    def test_no_java_is_refused_under_fast_too(self) -> None:
        # Given a machine with no java
        # When ./check --fast runs
        result = self.run_check("--fast")
        # Then it is refused, because --fast still runs ktlint through Gradle
        self.assert_jdk_refused(result, "absent")

    def test_java_21_is_refused(self) -> None:
        # Given java 21 on PATH
        # When ./check runs
        result = self.run_check(java="21", android_home=self.sdk())
        # Then it is refused, because a JDK 18+ API compiles locally and fails in CI
        self.assert_jdk_refused(result, "java(21")

    def test_java_8_is_refused(self) -> None:
        # Given java 8, which reports its specification version as 1.8
        # When ./check runs
        result = self.run_check("--fast", java="1.8")
        # Then it is refused
        self.assert_jdk_refused(result, "java(1.8")

    def test_java_that_prints_nothing_is_refused(self) -> None:
        # Given a java that exits non-zero without a version, like the macOS shim without a JDK
        # When ./check runs
        result = self.run_check("--fast", java=None)
        # Then it is refused as unusable rather than aborting the script
        self.assert_jdk_refused(result, "unusable")

    def test_invalid_java_home_is_refused_without_falling_back_to_path(self) -> None:
        # Given JAVA_HOME naming a missing directory and a good java 17 on PATH
        self.stub("java", java_stub("17"))
        # When ./check runs
        result = self.run_check("--fast", java_home=True)
        # Then it is refused, because gradlew dies on an invalid JAVA_HOME instead of using PATH
        self.assert_jdk_refused(result, "JAVA_HOME")

    def test_java_home_decides_over_path(self) -> None:
        # Given java 21 at JAVA_HOME and java 17 on PATH
        self.stub("java", java_stub("17"))
        # When ./check runs
        result = self.run_check("--fast", java="21", java_home=True)
        # Then it is refused, because gradlew uses JAVA_HOME's java
        self.assert_jdk_refused(result, "java(21")

    def test_java_17_clears_the_gate(self) -> None:
        # Given java 17 on PATH
        # When ./check --fast runs
        result = self.run_check("--fast", java="17")
        # Then no java finding is raised and the run reaches its first step
        self.assertNotIn("java(", result.stderr)
        self.assertIn(FIRST_STEP, result.stdout)

    # --- Android SDK: full runs only ---

    def assert_sdk_refused(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(127, result.returncode, result.stderr)
        self.assertIn("Android SDK", result.stderr)
        self.assertIn("local.properties", result.stderr)
        self.assertNotIn(FIRST_STEP, result.stdout)

    def assert_sdk_accepted(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertNotIn("Android SDK", result.stderr)
        self.assertIn(FIRST_STEP, result.stdout)

    def test_no_sdk_is_refused(self) -> None:
        # Given java 17 and no ANDROID_HOME, no ~/Android/Sdk and no local.properties
        # When ./check runs
        result = self.run_check(java="17")
        # Then it exits 127 before any step and points at local.properties
        self.assert_sdk_refused(result)

    def test_android_home_naming_a_missing_directory_is_refused(self) -> None:
        # Given ANDROID_HOME pointing nowhere
        # When ./check runs
        result = self.run_check(java="17", android_home=self.base / "gone")
        # Then it is refused, because Gradle would fail at the first Android task
        self.assert_sdk_refused(result)

    def test_no_sdk_does_not_stop_fast(self) -> None:
        # Given java 17 and no SDK
        # When ./check --fast runs
        result = self.run_check("--fast", java="17")
        # Then the SDK gate stays out of the way, because --fast compiles no Android module
        self.assert_sdk_accepted(result)

    def test_android_home_directory_is_accepted(self) -> None:
        # Given ANDROID_HOME naming a directory
        # When ./check runs
        result = self.run_check(java="17", android_home=self.sdk())
        # Then the SDK is found
        self.assert_sdk_accepted(result)

    def test_home_android_sdk_fallback_is_accepted(self) -> None:
        # Given the conventional ~/Android/Sdk and no ANDROID_HOME
        (self.home / "Android" / "Sdk").mkdir(parents=True)
        # When ./check runs
        result = self.run_check(java="17")
        # Then the SDK is found
        self.assert_sdk_accepted(result)

    def test_local_properties_sdk_dir_is_accepted(self) -> None:
        # Given an sdk.dir line in local.properties naming a directory, and no ANDROID_HOME
        (self.root / "local.properties").write_text(f"sdk.dir={self.sdk()}\n", encoding="utf-8")
        # When ./check runs
        result = self.run_check(java="17")
        # Then the SDK is found
        self.assert_sdk_accepted(result)

    def test_local_properties_sdk_dir_naming_a_missing_directory_is_refused(self) -> None:
        # Given a stale sdk.dir, which Gradle prefers over a good ANDROID_HOME
        (self.root / "local.properties").write_text("sdk.dir=/no/such/sdk\n", encoding="utf-8")
        # When ./check runs
        result = self.run_check(java="17", android_home=self.sdk())
        # Then it is refused
        self.assert_sdk_refused(result)


if __name__ == "__main__":
    unittest.main()
