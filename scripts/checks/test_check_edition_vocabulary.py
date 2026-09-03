#!/usr/bin/env python3
"""Self-check for check_edition_vocabulary.py.

A gate nobody has watched fail is not a gate. Every case here is either a divergence the rule must
report or a shape it must leave alone, and the parser cases matter most: this check compares two
literal lists, so a parser that stops understanding either file would report a clean gate over a
comparison it never made.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_edition_vocabulary import CORE_FILE, DEMO_FILE, findings

CORE_TEMPLATE = """package com.landofoz.musicmeta.provider.musicbrainz

internal object MusicBrainzQualifierFallback {{
    private val KIND_PATTERNS: List<KindPattern> = listOf(
{entries}
    )
}}
"""

DEMO_TEMPLATE = """package com.landofoz.musicmeta.demoweb

internal object EditionQualifier {{
    private val KIND_PATTERNS: List<KindPattern> = listOf(
{entries}
    )
}}
"""

SHARED = [("remaster", r"remaster(ed)?"), ("deluxe", r"deluxe(\s+edition)?")]


def entries(pairs: list[tuple[str, str]]) -> str:
    """`pairs` rendered as the `KindPattern` lines both files declare."""
    return "\n".join(
        f'        KindPattern("{kind}", Regex("""{pattern}""", RegexOption.IGNORE_CASE)),' for kind, pattern in pairs
    )


class EditionVocabularyTest(unittest.TestCase):
    def run_against(self, core: str, demo: str) -> list[str]:
        """Findings for a tree holding these two source files."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, body in ((CORE_FILE, core), (DEMO_FILE, demo)):
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")
            return findings(root)

    def run_kinds(self, core: list[tuple[str, str]], demo: list[tuple[str, str]]) -> list[str]:
        """Findings for two `KIND_PATTERNS` lists rendered into their real declarations."""
        return self.run_against(
            CORE_TEMPLATE.format(entries=entries(core)), DEMO_TEMPLATE.format(entries=entries(demo))
        )

    # --- the rule fires ---

    def test_reports_a_kind_core_strips_and_demo_web_does_not(self) -> None:
        # Given core gained a kind that was never copied across
        found = self.run_kinds([*SHARED, ("box_set", r"box\s*set")], SHARED)
        # When the vocabulary check runs
        # Then it names the missing kind and where to copy it from
        self.assertEqual(1, len(found))
        self.assertIn("core strips the edition kind `box_set` and demo-web does not", found[0])

    def test_reports_a_kind_only_demo_web_strips(self) -> None:
        # Given demo-web collapses a kind core will not search for
        found = self.run_kinds(SHARED, [*SHARED, ("box_set", r"box\s*set")])
        # Then the check reports it as a divergence in its own right
        self.assertEqual(1, len(found))
        self.assertIn("demo-web strips the edition kind `box_set` and core does not", found[0])

    def test_reports_a_pattern_that_drifted_under_a_shared_kind(self) -> None:
        # Given a kind present in both files whose regex core has since widened
        found = self.run_kinds([("remaster", r"remaster(ed)?(\s+version)?"), SHARED[1]], SHARED)
        # Then the check quotes both literals rather than reporting only that they differ
        self.assertEqual(1, len(found))
        self.assertIn("the `remaster` pattern has drifted", found[0])
        self.assertIn(r"remaster(ed)?(\s+version)?", found[0])

    def test_reports_the_same_kinds_declared_in_a_different_order(self) -> None:
        # Given identical kinds and patterns, reordered so a phrase classifies as the broader kind
        found = self.run_kinds(SHARED, list(reversed(SHARED)))
        # Then the check reports the order, because the first fullmatch is what wins
        self.assertEqual(1, len(found))
        self.assertIn("the kinds match but their order does not", found[0])

    def test_reports_every_divergence_rather_than_only_the_first(self) -> None:
        # Given two independent divergences in one file
        found = self.run_kinds(
            [("remaster", r"remaster(ed)?(\s+version)?"), SHARED[1], ("box_set", r"box\s*set")], SHARED
        )
        # Then both are reported, so one fix does not hide the other
        self.assertEqual(2, len(found))

    # --- the rule leaves the legitimate shapes alone ---

    def test_accepts_two_identical_vocabularies(self) -> None:
        # Given the two lists in sync
        found = self.run_kinds(SHARED, SHARED)
        # Then there is nothing to report
        self.assertEqual([], found)

    def test_ignores_the_deliberate_asymmetry_outside_the_pattern_list(self) -> None:
        # Given demo-web's looser sequence matching and core's KIND_KEYWORDS, neither duplicated
        core = CORE_TEMPLATE.format(entries=entries(SHARED)).replace(
            "}\n", '    private val KIND_KEYWORDS = mapOf("remaster" to listOf("remaster"))\n}\n'
        )
        demo = DEMO_TEMPLATE.format(entries=entries(SHARED)).replace(
            "}\n", "    fun isQualifierPhrase(p: String) = true\n}\n"
        )
        found = self.run_against(core, demo)
        # Then the check stays silent — only the vocabulary is duplicated, so only it is compared
        self.assertEqual([], found)

    # --- the parser cannot pass by understanding nothing ---

    def test_fails_when_a_file_no_longer_declares_the_list(self) -> None:
        # Given demo-web's declaration renamed out from under the parser
        core = CORE_TEMPLATE.format(entries=entries(SHARED))
        demo = DEMO_TEMPLATE.format(entries=entries(SHARED)).replace(
            "KIND_PATTERNS: List<KindPattern>", "KINDS: List<KindPattern>"
        )
        found = self.run_against(core, demo)
        # Then the check fails loudly instead of comparing nothing and reporting clean
        self.assertEqual(1, len(found))
        self.assertIn("the edition vocabulary was never compared", found[0])

    def test_fails_when_a_declaration_parses_to_no_entries(self) -> None:
        # Given a list the entry pattern cannot read
        core = CORE_TEMPLATE.format(entries=entries(SHARED))
        demo = DEMO_TEMPLATE.format(entries="        KindPattern(REMASTER_KIND, REMASTER_PATTERN),")
        found = self.run_against(core, demo)
        # Then it is a finding, not a silent pass over zero entries
        self.assertEqual(1, len(found))
        self.assertIn("parsed to zero entries", found[0])

    def test_fails_when_a_source_file_has_moved(self) -> None:
        # Given a tree in which core's file is not where the check expects it
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            demo_path = root / DEMO_FILE
            demo_path.parent.mkdir(parents=True, exist_ok=True)
            demo_path.write_text(DEMO_TEMPLATE.format(entries=entries(SHARED)), encoding="utf-8")
            found = findings(root)
        # Then the check reports the missing path rather than scanning an empty tree
        self.assertEqual(1, len(found))
        self.assertIn("is missing, so the edition vocabulary was never compared", found[0])


if __name__ == "__main__":
    unittest.main()
