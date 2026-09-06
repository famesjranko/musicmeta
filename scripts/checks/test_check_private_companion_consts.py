#!/usr/bin/env python3
"""Self-check for check_private_companion_consts.py.

A gate nobody has watched fail is not a gate, so the rule is proved to fire on the exact violation
it exists to catch — the six constants the sweep removed from the api dumps all had this shape — and
proved not to fire on the three things most likely to be mistaken for one: the same constant written
`private`, the same constant in a public companion, and a companion that has already closed.

Run with: python3 test_check_private_companion_consts.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_private_companion_consts import main, run  # noqa: E402


class PrivateCompanionConstsTest(unittest.TestCase):
    def findings_for(self, body: str, *, rel: str = "musicmeta-core/src/main/kotlin/P.kt") -> list[str]:
        """Findings for a tree holding just this one file."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding="utf-8")
            return run(root)

    def test_bare_const_in_private_companion_is_reported(self) -> None:
        findings = self.findings_for(
            'class P {\n    private companion object {\n        const val TAG = "P"\n    }\n}\n'
        )
        self.assertEqual(1, len(findings), findings)
        self.assertIn("line=3", findings[0])
        self.assertIn("TAG", findings[0])

    def test_private_const_in_private_companion_passes(self) -> None:
        self.assertEqual(
            [],
            self.findings_for(
                'class P {\n    private companion object {\n        private const val TAG = "P"\n    }\n}\n'
            ),
        )

    def test_const_in_a_public_companion_passes(self) -> None:
        """A public companion's constant is a published one on purpose, which is a different thing."""
        self.assertEqual(
            [],
            self.findings_for(
                "class P {\n"
                "    public companion object {\n"
                "        public const val DEFAULT_IMAGE_SIZE: Int = 1200\n"
                "    }\n"
                "}\n"
            ),
        )

    def test_a_const_after_the_companion_closes_passes(self) -> None:
        """The scan must stop at the companion's own closing brace, not run to end of file."""
        self.assertEqual(
            [],
            self.findings_for(
                "class P {\n"
                "    private companion object {\n"
                '        private const val TAG = "P"\n'
                "    }\n"
                "}\n"
                "\n"
                "object Q {\n"
                "    const val PUBLISHED = 1\n"
                "}\n"
            ),
        )

    def test_test_sources_are_not_scanned(self) -> None:
        """Test sources publish nothing, and several of them use this exact shape for a pool name."""
        self.assertEqual(
            [],
            self.findings_for(
                "class T {\n"
                "    private companion object {\n"
                '        const val POOL = "wikidata-enwiki-sitelink"\n'
                "    }\n"
                "}\n",
                rel="musicmeta-core/src/test/kotlin/T.kt",
            ),
        )

    def test_a_tree_with_no_main_sources_fails_rather_than_passing(self) -> None:
        """A scan that read nothing must report, not report clean."""
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(2, main(["--root", tmp]))


if __name__ == "__main__":
    unittest.main()
