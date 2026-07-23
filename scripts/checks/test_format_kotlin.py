#!/usr/bin/env python3
"""Self-test for scripts/format-kotlin.sh.

The hook's failure mode is not an error — it is exiting 0 having formatted nothing, which looks
identical to success. That is what it did for every Bash-written file before #59, and it is what a
half-fix would keep doing: widening the PostToolUse matcher to `Bash` without touching the script
leaves it reading `tool_input.file_path`, which a Bash payload does not carry. So these assert on
the *selected targets*, via `--print-targets`, not on exit codes. That mode also means the test
needs no ktlint CLI, which is optional by design.

The Stop sweep replaced an mtime window that tried to attribute files to the command that just ran.
Several cases below pin properties that heuristic could not have had: a file written long before
the sweep is still formatted, and a path containing a space or a quote survives parsing.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "format-kotlin.sh"
GIT_ID = ["-c", "user.email=t@example.com", "-c", "user.name=t"]


def run(cwd: Path, *args: str, payload: dict | str | None = None) -> list[str]:
    """Run the hook and return the files it would format."""
    stdin = "" if payload is None else payload if isinstance(payload, str) else json.dumps(payload)
    result = subprocess.run(
        [str(SCRIPT), "--print-targets", *args],
        input=stdin,
        capture_output=True,
        text=True,
        cwd=cwd,
        check=False,
    )
    if result.returncode != 0:
        raise AssertionError(f"hook exited {result.returncode}: {result.stderr}")
    return [line for line in result.stdout.splitlines() if line]


def commit_all(repo: Path) -> None:
    subprocess.run(["git", "add", "."], cwd=repo, check=True)
    subprocess.run(["git", *GIT_ID, "commit", "-qm", "x"], cwd=repo, check=True)


class TempRepo:
    """A throwaway git repo, because the sweep reads `git status`."""

    def __enter__(self) -> Path:
        self._tmp = tempfile.TemporaryDirectory()
        self.path = Path(self._tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.path, check=True)
        return self.path

    def __exit__(self, *_: object) -> None:
        self._tmp.cleanup()


class PayloadMode(unittest.TestCase):
    """PostToolUse on Edit|Write: the payload names the file, so no git call and no guessing."""

    def test_edit_payload_selects_the_named_kotlin_file(self) -> None:
        # Given — a payload naming a .kt file that exists
        with TempRepo() as repo:
            (repo / "Thing.kt").write_text("class Thing\n")

            # When / Then — that file, and only it
            selected = run(repo, payload={"tool_input": {"file_path": str(repo / "Thing.kt")}})
            self.assertEqual(selected, [str(repo / "Thing.kt")])

    def test_non_kotlin_file_path_selects_nothing(self) -> None:
        # Given — a payload naming a Markdown file
        with TempRepo() as repo:
            (repo / "README.md").write_text("# hi\n")

            # When / Then — nothing to format
            payload = {"tool_input": {"file_path": str(repo / "README.md")}}
            self.assertEqual(run(repo, payload=payload), [])

    def test_missing_file_selects_nothing(self) -> None:
        # Given — a payload naming a path that no longer exists
        with TempRepo() as repo:
            # When / Then — no attempt to format what is not there
            payload = {"tool_input": {"file_path": str(repo / "Gone.kt")}}
            self.assertEqual(run(repo, payload=payload), [])

    def test_bash_shaped_payload_selects_nothing_in_this_mode(self) -> None:
        # Given — a Bash payload, which carries `command` and never `file_path`
        with TempRepo() as repo:
            (repo / "Written.kt").write_text("class Written\n")

            # When / Then — payload mode does not guess. The Stop sweep covers this write, and
            # conflating the two is where the mtime heuristic got its false positives.
            payload = {"tool_input": {"command": "sed -i s/a/b/ Written.kt"}}
            self.assertEqual(run(repo, payload=payload), [])

    def test_unparseable_stdin_selects_nothing(self) -> None:
        # Given — stdin that is not JSON
        with TempRepo() as repo:
            (repo / "Written.kt").write_text("class Written\n")

            # When / Then — it stops rather than falling through to a sweep. Formatting whatever
            # happened to be dirty, because a payload was unreadable, is behaviour by accident.
            self.assertEqual(run(repo, payload="not json"), [])

    def test_payload_without_tool_input_selects_nothing(self) -> None:
        # Given — a well-formed payload with no tool_input at all
        with TempRepo() as repo:
            # When / Then — no crash, nothing selected
            self.assertEqual(run(repo, payload={"hook_event_name": "PostToolUse"}), [])


class SweepMode(unittest.TestCase):
    """Stop hook: end of turn, format every dirty Kotlin file however it came to be dirty."""

    def test_untracked_kotlin_is_swept(self) -> None:
        # Given — a .kt file written by something that named no file, a heredoc say
        with TempRepo() as repo:
            (repo / "Written.kt").write_text("class Written\n")

            # When / Then — git found what no payload could name
            self.assertEqual(run(repo, "--sweep"), ["Written.kt"])

    def test_modified_tracked_kotlin_is_swept(self) -> None:
        # Given — a committed file since modified in place
        with TempRepo() as repo:
            target = repo / "Tracked.kt"
            target.write_text("class Tracked\n")
            commit_all(repo)
            target.write_text("class Tracked2\n")

            # When / Then — modification counts as dirt, not only untracked files
            self.assertEqual(run(repo, "--sweep"), ["Tracked.kt"])

    def test_non_kotlin_changes_are_ignored(self) -> None:
        # Given — a turn that touched only Python
        with TempRepo() as repo:
            (repo / "script.py").write_text("x = 1\n")

            # When / Then — nothing to format, and no ktlint JVM paid for
            self.assertEqual(run(repo, "--sweep"), [])

    def test_old_mtime_is_still_swept(self) -> None:
        # Given — a dirty file with an ancient mtime. The window this replaced would have skipped
        # it; `cp -p`, `rsync -t` and archive extraction all produce exactly this.
        with TempRepo() as repo:
            stale = repo / "Old.kt"
            stale.write_text("class Old\n")
            os.utime(stale, (0, 0))

            # When / Then — dirtiness is the criterion, not recency
            self.assertEqual(run(repo, "--sweep"), ["Old.kt"])

    def test_deleted_file_is_not_a_target(self) -> None:
        # Given — a tracked .kt file deleted this turn
        with TempRepo() as repo:
            target = repo / "Doomed.kt"
            target.write_text("class Doomed\n")
            commit_all(repo)
            target.unlink()

            # When / Then — git reports it, but there is nothing on disk to format
            self.assertEqual(run(repo, "--sweep"), [])

    def test_paths_with_spaces_and_quotes_survive(self) -> None:
        # Given — filenames git quotes in its default porcelain output. `git status -z` and
        # NUL-separated parsing are why these arrive intact rather than as `"a file.kt"`.
        with TempRepo() as repo:
            for name in ("a file.kt", "it's.kt"):
                (repo / name).write_text("class X\n")

            # When / Then — both, unmangled
            self.assertEqual(sorted(run(repo, "--sweep")), ["a file.kt", "it's.kt"])

    def test_renamed_kotlin_selects_only_the_destination(self) -> None:
        # Given — a staged rename, which git reports as one record carrying two paths
        with TempRepo() as repo:
            (repo / "Before.kt").write_text("class Before\n")
            commit_all(repo)
            subprocess.run(["git", "mv", "Before.kt", "After.kt"], cwd=repo, check=True)

            # When / Then — only the file that exists. Misreading the record yields the vanished
            # original and a spurious formatter failure.
            self.assertEqual(run(repo, "--sweep"), ["After.kt"])

    def test_clean_tree_selects_nothing(self) -> None:
        # Given — a turn that changed nothing
        with TempRepo() as repo:
            # When / Then — the common case is one git call and out
            self.assertEqual(run(repo, "--sweep"), [])

    def test_outside_a_git_repo_selects_nothing(self) -> None:
        # Given — a directory that is not a repo at all
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "Loose.kt").write_text("class Loose\n")

            # When / Then — exits cleanly rather than erroring inside the write path
            self.assertEqual(run(Path(tmp), "--sweep"), [])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
