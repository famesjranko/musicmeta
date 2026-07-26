#!/usr/bin/env python3
"""Self-check for check_provider_capabilities.py.

A gate nobody has watched fail is not a gate. Every rule here is proved to fire on the exact
disagreement it exists to catch — in both directions, because a doc claiming a dropped capability is
as wrong as a doc missing a gained one — and every shape the parser refuses to read is proved to
fail loudly rather than pass by default. Run with: python3 test_check_provider_capabilities.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_provider_capabilities import DOC_ROOT, ENUM_SOURCE, PROVIDER_ROOT, run  # noqa: E402

ENUM = """package com.landofoz.musicmeta

enum class EnrichmentType(val defaultTtlMs: Long) {
    GENRE(1),
    ARTIST_BIO(2),
    SIMILAR_ARTISTS(3),
    ARTIST_RADIO_DISCOVERY(4),
}
"""

TWO_CAPABILITIES = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        ProviderCapability(EnrichmentType.ARTIST_BIO, priority = 50),
    )
}
"""


def doc(*names: str, heading: str = "## What We Extract") -> str:
    rows = "\n".join(f"| `{name}` | ForArtist | call | fields |" for name in names)
    return f"""# Foo

{heading}

| EnrichmentType | Request | Upstream call | What we keep |
|---|---|---|---|
{rows}

## What We DON'T Extract

Nothing.
"""


class ProviderCapabilitiesTest(unittest.TestCase):
    def findings_for(self, sources: dict[str, str], docs: dict[str, str]) -> list[str]:
        """Given provider sources keyed by `<package>/<File>.kt` and docs keyed by `<name>.md`."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root / ENUM_SOURCE, ENUM)
            (root / DOC_ROOT).mkdir(parents=True, exist_ok=True)
            for rel, body in sources.items():
                self.write(root / PROVIDER_ROOT / rel, body)
            for rel, body in docs.items():
                self.write(root / DOC_ROOT / rel, body)
            return run(root)

    def write(self, path: Path, body: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    # --- the set comparison, both directions ---

    def test_matching_doc_and_code_pass(self):
        # Given a doc listing exactly the capabilities the provider declares
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES},
            {"foo.md": doc("GENRE", "ARTIST_BIO")},
        )
        # Then nothing is reported
        self.assertEqual(findings, [])

    def test_capability_declared_in_code_but_missing_from_the_doc_is_reported(self):
        # Given a doc that has fallen behind a capability the code gained
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES},
            {"foo.md": doc("GENRE")},
        )
        # Then the missing type is named, against the doc
        self.assertEqual(len(findings), 1)
        self.assertIn("docs/providers/foo.md", findings[0])
        self.assertIn("ARTIST_BIO", findings[0])

    def test_capability_claimed_by_the_doc_but_not_declared_is_reported(self):
        # Given a doc still claiming a capability the code dropped — the other direction, and the
        # one a "did you document the new feature" habit never catches
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES},
            {"foo.md": doc("GENRE", "ARTIST_BIO", "SIMILAR_ARTISTS")},
        )
        # Then the surplus claim is named
        self.assertEqual(len(findings), 1)
        self.assertIn("SIMILAR_ARTISTS", findings[0])
        self.assertIn("not declared", findings[0])

    def test_a_name_that_is_not_an_enrichment_type_is_reported_as_a_typo(self):
        # Given a doc naming something the enum does not have
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES},
            {"foo.md": doc("GENRE", "ARTIST_BIOGRAPHY")},
        )
        # Then it is called out as not being a constant, rather than as a mere difference
        self.assertEqual(len(findings), 1)
        self.assertIn("not `EnrichmentType` constants", findings[0])
        self.assertIn("ARTIST_BIOGRAPHY", findings[0])

    def test_two_provider_classes_in_one_file_are_both_read(self):
        # Given two classes sharing a file — reading only the first would under-report the package
        source = (
            TWO_CAPABILITIES
            + """
class ExtraProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100),
    )
}
"""
        )
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE", "ARTIST_BIO")})
        # Then the second class's capability is missing from the doc, and says so
        self.assertEqual(len(findings), 1)
        self.assertIn("SIMILAR_ARTISTS", findings[0])

    def test_capabilities_in_a_subdirectory_are_read(self):
        # Given a provider nested below the package root, which a non-recursive glob would miss
        nested = """package p

class RadioProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100),
    )
}
"""
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES, "foo/radio/RadioProvider.kt": nested},
            {"foo.md": doc("GENRE", "ARTIST_BIO")},
        )
        self.assertEqual(len(findings), 1)
        self.assertIn("SIMILAR_ARTISTS", findings[0])

    def test_capabilities_in_a_file_not_named_provider_are_read(self):
        # Given capabilities in a helper file — `*Provider.kt` is a convention, not a guarantee
        helper = """package p

internal val EXTRA = object : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100),
    )
}
"""
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES, "foo/Extras.kt": helper},
            {"foo.md": doc("GENRE", "ARTIST_BIO")},
        )
        self.assertEqual(len(findings), 1)
        self.assertIn("SIMILAR_ARTISTS", findings[0])

    def test_capabilities_from_two_provider_files_in_one_package_are_unioned(self):
        # Given a package with a second provider class — deezer's SimilarAlbumsProvider shape
        second = """package p

class BarProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100),
    )
}
"""
        # When the doc covers the package rather than one class
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES, "foo/BarProvider.kt": second},
            {"foo.md": doc("GENRE", "ARTIST_BIO", "SIMILAR_ARTISTS")},
        )
        # Then both files' capabilities count
        self.assertEqual(findings, [])

    # --- shapes the parser must read ---

    def test_a_conditionally_built_list_is_read_whole(self):
        # Given ListenBrainz's shape: buildList, with one capability gated on an auth token. The
        # doc describes the package, so a capability the code can decline to register still counts.
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities: List<ProviderCapability> = buildList {
        add(ProviderCapability(type = EnrichmentType.GENRE, priority = PRIORITY))
        if (authToken != null) {
            add(ProviderCapability(type = EnrichmentType.ARTIST_RADIO_DISCOVERY, priority = PRIORITY))
        }
    }
}
"""
        findings = self.findings_for(
            {"foo/FooProvider.kt": source},
            {"foo.md": doc("GENRE", "ARTIST_RADIO_DISCOVERY")},
        )
        # Then both are read
        self.assertEqual(findings, [])

    def test_a_type_named_only_in_a_comment_is_not_counted(self):
        # Given a commented-out capability — the shape that would otherwise inflate the code's set
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        // ProviderCapability(EnrichmentType.SIMILAR_ARTISTS, priority = 100),
    )
}
"""
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then the comment is invisible to both the count and the set
        self.assertEqual(findings, [])

    # --- shapes the parser refuses to read: loud, never a silent pass ---

    def test_a_capability_list_built_by_a_helper_fails_loudly(self):
        # Given a list assembled by a call whose result the parser cannot see
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
    ) + sharedArtworkCapabilities()
}
"""
        # When the doc happens to match what the parser *can* see
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then it fails anyway, naming the file — it never passes on a shape it did not understand
        self.assertEqual(len(findings), 1)
        self.assertIn("FooProvider.kt", findings[0])
        self.assertIn("sharedArtworkCapabilities", findings[0])

    def test_a_capability_list_derived_from_the_enum_fails_loudly(self):
        # Given capabilities generated from the enum, so no literal name appears
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = buildList {
        ARTWORK_TYPES.forEach { add(ProviderCapability(it, priority = 100)) }
    }
}
"""
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then the unreadable shape is reported, not the (meaningless) set difference
        self.assertEqual(len(findings), 1)
        self.assertIn("FooProvider.kt", findings[0])

    def test_a_shared_capability_referenced_by_name_fails_loudly(self):
        # Given a capability contributed as a value rather than a call — the natural way to share
        # one, and invisible to any parser that only looks for `ProviderCapability(`
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        SHARED_RADIO_CAPABILITY,
    )
}
"""
        # When the doc matches only what the parser can see
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then it fails, naming the token it could not account for
        self.assertEqual(len(findings), 1)
        self.assertIn("SHARED_RADIO_CAPABILITY", findings[0])

    def test_an_addall_of_a_shared_list_fails_loudly(self):
        # Given the other way to bulk-add capabilities out of the parser's sight
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = buildList {
        add(ProviderCapability(EnrichmentType.GENRE, priority = 100))
        addAll(ARTWORK_CAPABILITIES)
    }
}
"""
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then it fails rather than reporting a set difference it cannot know is real
        self.assertEqual(len(findings), 1)
        self.assertIn("addAll", findings[0])

    def test_a_package_that_declares_no_capabilities_at_all_fails_loudly(self):
        # Given a documented package where nothing declares capabilities
        source = 'package p\n\nclass FooProvider : EnrichmentProvider {\n    override val id = "foo"\n}\n'
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then the package is named, rather than silently comparing against an empty set
        self.assertEqual(len(findings), 1)
        self.assertIn("no `override val capabilities` in the package", findings[0])

    def test_a_helper_file_without_capabilities_is_not_a_failure(self):
        # Given the musicbrainz shape: extra files beside the provider, none declaring capabilities
        helper = "package p\n\ninternal object FooParser {\n    fun parse() = Unit\n}\n"
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES, "foo/FooParser.kt": helper},
            {"foo.md": doc("GENRE", "ARTIST_BIO")},
        )
        # Then only files that actually declare capabilities are held to the shape rules
        self.assertEqual(findings, [])

    def test_an_unparseable_table_row_fails_loudly(self):
        # Given a row in the table whose first cell is not a capability claim — the shape that would
        # otherwise let a capability quietly drop out of the comparison
        body = doc("GENRE").replace("| `GENRE` |", "| Bio summary |")
        findings = self.findings_for({"foo/FooProvider.kt": TWO_CAPABILITIES}, {"foo.md": body})
        # Then the row is reported at its line, not skipped
        self.assertEqual(len(findings), 1)
        self.assertIn("'Bio summary'", findings[0])

    def test_a_doc_without_the_section_fails_loudly(self):
        # Given a provider doc with no `## What We Extract` at all
        body = doc("GENRE", heading="## What We Take")
        findings = self.findings_for({"foo/FooProvider.kt": TWO_CAPABILITIES}, {"foo.md": body})
        # Then it fails rather than reading zero capabilities and comparing an empty set
        self.assertEqual(len(findings), 1)
        self.assertIn("no `## What We Extract` section", findings[0])

    def test_a_second_extract_section_fails_loudly(self):
        # Given two `## What We Extract` headings — only the first is read, so the second's claims
        # would go unchecked
        body = doc("GENRE", "ARTIST_BIO") + "\n## What We Extract\n\n| `SIMILAR_ARTISTS` | a | b | c |\n"
        findings = self.findings_for({"foo/FooProvider.kt": TWO_CAPABILITIES}, {"foo.md": body})
        self.assertEqual(len(findings), 1)
        self.assertIn("a second", findings[0])

    def test_a_fenced_block_in_the_section_is_not_read_as_claims(self):
        # Given a code fence showing the row template inside the section
        body = doc("GENRE", "ARTIST_BIO").replace(
            "## What We DON'T Extract",
            "```\n| `SIMILAR_ARTISTS` | Request | call | fields |\n```\n\n## What We DON'T Extract",
        )
        findings = self.findings_for({"foo/FooProvider.kt": TWO_CAPABILITIES}, {"foo.md": body})
        # Then the illustration is not mistaken for a capability the code fails to declare
        self.assertEqual(findings, [])

    def test_a_subsection_ends_the_table(self):
        # Given a `###` subsection with a table of its own, below the capability table
        body = doc("GENRE", "ARTIST_BIO").replace(
            "## What We DON'T Extract",
            "### Field notes\n\n| Field | Where |\n|---|---|\n| bio | summary |\n\n## What We DON'T Extract",
        )
        findings = self.findings_for({"foo/FooProvider.kt": TWO_CAPABILITIES}, {"foo.md": body})
        # Then its rows are not read as capability claims
        self.assertEqual(findings, [])

    # --- bracket matching does not trust comments ---

    def test_a_stray_close_paren_in_a_comment_does_not_truncate_the_list(self):
        # Given a comment carrying an unbalanced `)` between two capabilities. Stripping comments
        # after bracket matching would end the list here and hide everything below it.
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = listOf(
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
        // deprioritised (see the note on bio quality) below)
        ProviderCapability(EnrichmentType.ARTIST_BIO, priority = 50),
    )
}
"""
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        # Then ARTIST_BIO is still seen, and the stale doc still fails
        self.assertEqual(len(findings), 1)
        self.assertIn("ARTIST_BIO", findings[0])

    def test_a_stray_open_paren_in_a_comment_is_not_a_failure(self):
        # Given the mirror case, which would otherwise report an unclosed bracket
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = listOf(
        // tags-as-genre (see docs/pitfalls.md
        ProviderCapability(EnrichmentType.GENRE, priority = 100),
    )
}
"""
        findings = self.findings_for({"foo/FooProvider.kt": source}, {"foo.md": doc("GENRE")})
        self.assertEqual(findings, [])

    # --- conditions are not inspected ---

    def test_an_idiomatic_condition_calling_a_function_is_accepted(self):
        # Given `if (apiKeyProvider().isNotBlank())` — the house idiom for a key check. What a
        # condition tests is none of this check's business; only what the branches add is.
        source = """package p

class FooProvider : EnrichmentProvider {
    override val capabilities = buildList {
        add(ProviderCapability(EnrichmentType.GENRE, priority = 100))
        if (apiKeyProvider().isNotBlank()) {
            add(ProviderCapability(EnrichmentType.ARTIST_BIO, priority = 50))
        }
    }
}
"""
        findings = self.findings_for(
            {"foo/FooProvider.kt": source},
            {"foo.md": doc("GENRE", "ARTIST_BIO")},
        )
        self.assertEqual(findings, [])

    # --- the check cannot disable itself ---

    def test_a_missing_doc_root_is_a_failure_not_a_green_run(self):
        # Given no docs/providers/ at all — what deleting or moving the directory would produce
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root / ENUM_SOURCE, ENUM)
            self.write(root / PROVIDER_ROOT / "foo/FooProvider.kt", TWO_CAPABILITIES)
            findings = run(root)
        # Then it fails, rather than reporting green having compared nothing
        self.assertEqual(len(findings), 1)
        self.assertIn("provider doc root is missing", findings[0])

    def test_a_missing_enum_source_is_a_failure_not_a_traceback(self):
        # Given EnrichmentType.kt moved out from under the check
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root / PROVIDER_ROOT / "foo/FooProvider.kt", TWO_CAPABILITIES)
            self.write(root / DOC_ROOT / "foo.md", doc("GENRE", "ARTIST_BIO"))
            findings = run(root)
        # Then it is reported as a finding like any other, not raised as a FileNotFoundError
        self.assertEqual(len(findings), 1)
        self.assertIn("is missing", findings[0])

    # --- incremental landing, and dangling docs ---

    def test_a_provider_with_no_doc_yet_is_skipped(self):
        # Given a package documented by nothing — feature docs land one provider at a time
        findings = self.findings_for({"foo/FooProvider.kt": TWO_CAPABILITIES}, {})
        # Then it is not a failure
        self.assertEqual(findings, [])

    def test_a_doc_with_no_package_is_reported(self):
        # Given a doc left behind by a renamed or deleted provider package
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES},
            {"foo.md": doc("GENRE", "ARTIST_BIO"), "spotify.md": doc("GENRE")},
        )
        # Then the orphan is named — the dangling-file failure a directory of docs invites
        self.assertEqual(len(findings), 1)
        self.assertIn("spotify.md", findings[0])

    def test_the_index_readme_is_not_treated_as_a_provider(self):
        # Given the directory's own index alongside the provider docs
        findings = self.findings_for(
            {"foo/FooProvider.kt": TWO_CAPABILITIES},
            {"foo.md": doc("GENRE", "ARTIST_BIO"), "README.md": "# Index\n"},
        )
        # Then it is left alone
        self.assertEqual(findings, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
