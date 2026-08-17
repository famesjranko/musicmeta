#!/usr/bin/env python3
"""Self-check for check_doc_samples.py.

Covers the classification rule (top-level vs. wrapped, and the two things that force a wrap even
past a `val`/`var` fast path), the modifier-stripping fix `leading_declaration` exists for, the
opt-out marker (present, absent, empty-reason), and the per-guide accumulation this script's second
version added: narrative concatenation in fence order, `run { }` isolation for a rebound name, the
`ExtractionError` for a name a fence redeclares within itself, and sub-package isolation for a
top-level fence whose declared name would otherwise shadow a real type or an earlier top-level fence.
Run with: python3 test_check_doc_samples.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_doc_samples import (  # noqa: E402
    ExtractionError,
    build_guide,
    classify,
    depth0_declared_names,
    guide_package,
    leading_declaration,
    public_packages,
    public_simple_names,
    run,
)


def fence(*body_lines: str) -> list[str]:
    return list(body_lines)


class LeadingDeclarationTest(unittest.TestCase):
    def test_unmodified_declaration(self):
        # Given a plain declaration line
        # When its keyword and name are read
        keyword, name = leading_declaration("val engine = EnrichmentEngine.Builder().build()")
        # Then both are found
        self.assertEqual((keyword, name), ("val", "engine"))

    def test_modified_declaration(self):
        # Given a declaration preceded by modifiers, the case NAME_RE.match(line) used to miss
        # When its keyword and name are read
        keyword, name = leading_declaration("sealed class EnrichmentResult {")
        # Then the name still resolves — the match starts after the modifiers, not at the line start
        self.assertEqual((keyword, name), ("class", "EnrichmentResult"))

    def test_bare_statement_has_no_keyword_or_name(self):
        # Given a line that is not a declaration at all
        # When it is read
        keyword, name = leading_declaration("engine.invalidate(request)")
        # Then neither is found
        self.assertEqual((keyword, name), (None, None))

    def test_annotation_has_a_keyword_but_no_name(self):
        # Given a bare annotation line
        # When it is read
        keyword, name = leading_declaration("@OptIn(MusicmetaTestApi::class)")
        # Then the keyword is the sentinel and there is no name to extract
        self.assertEqual((keyword, name), ("@", None))


class ClassifyTest(unittest.TestCase):
    def test_import_only_fence_is_top_level(self):
        # Given a fence that is only an import
        # When it is classified
        result = classify(fence("import com.landofoz.musicmeta.engine.ResultMerger"))
        # Then it is left at file scope
        self.assertEqual(result, "top-level")

    def test_bare_statement_forces_a_wrap(self):
        # Given a fence whose only line is a method call, not a declaration
        # When it is classified
        result = classify(fence("engine.invalidate(request)"))
        # Then it is wrapped, since a bare statement is illegal at file scope
        self.assertEqual(result, "wrap")

    def test_val_forces_a_wrap_even_though_it_is_a_declaration(self):
        # Given a fence whose only line is a `val`
        # When it is classified
        result = classify(fence("val engine = EnrichmentEngine.Builder().build()"))
        # Then it is wrapped — a top-level suspend call needs a coroutine, a local one does not
        self.assertEqual(result, "wrap")

    def test_duplicate_top_level_name_forces_a_wrap(self):
        # Given a fence declaring the same top-level name twice
        # When it is classified
        result = classify(
            fence(
                "val engine = EnrichmentEngine.Builder().build()",
                "val engine = EnrichmentEngine.Builder().cache(cache).build()",
            )
        )
        # Then it is wrapped — Kotlin forbids the file-scope redeclaration local shadowing allows
        self.assertEqual(result, "wrap")

    def test_named_type_alongside_val_stays_top_level(self):
        # Given a fence declaring a named object and a val that registers it
        # When it is classified
        result = classify(
            fence(
                "object MyMerger : ResultMerger {",
                "    override val type = EnrichmentType.GENRE",
                "}",
                "val engine = EnrichmentEngine.Builder().addMerger(MyMerger).build()",
            )
        )
        # Then it stays at file scope — a named type cannot be local, so wrapping would break it
        self.assertEqual(result, "top-level")

    def test_fluent_chain_continuation_is_not_a_fresh_statement(self):
        # Given a named type alongside a val whose builder chain continues on later lines with `.`
        # When it is classified
        result = classify(
            fence(
                "object MyMerger : ResultMerger {",
                "    override val type = EnrichmentType.GENRE",
                "}",
                "val engine = EnrichmentEngine.Builder()",
                "    .addMerger(MyMerger)",
                "    .build()",
            )
        )
        # Then the `.addMerger(...)`/`.build()` lines are not read as bare statements of their own —
        # a misread here would force a wrap and break the named `object` above, the exact bug this
        # fixes (see `classify`'s continuation-line branch)
        self.assertEqual(result, "top-level")

    def test_modified_top_level_declaration_stays_top_level(self):
        # Given a fence whose only line is a modified declaration (the case leading_declaration fixes)
        # When it is classified
        result = classify(fence("sealed class EnrichmentResult {", "}"))
        # Then it is left at file scope
        self.assertEqual(result, "top-level")


class DepthZeroDeclaredNamesTest(unittest.TestCase):
    def test_only_val_and_var_are_reported(self):
        # Given a fence with a fun, a val and a bare statement
        # When its declared names are read
        names = depth0_declared_names(fence("fun helper() {}", "val engine = build()", "engine.run()"))
        # Then only the val's name is reported — funs are not what accumulation isolation cares about
        self.assertEqual(names, ["engine"])

    def test_duplicates_are_kept_not_deduplicated(self):
        # Given a fence declaring the same name twice
        # When its declared names are read
        names = depth0_declared_names(fence("val engine = a()", "val engine = b()"))
        # Then both are reported — the caller decides what a duplicate means
        self.assertEqual(names, ["engine", "engine"])


class BuildGuideTest(unittest.TestCase):
    def write_guide(self, tmp: Path, body: str, name: str = "sample.md") -> Path:
        guides = tmp / "docs" / "guides"
        guides.mkdir(parents=True, exist_ok=True)
        path = guides / name
        path.write_text(body, encoding="utf-8")
        return path

    def test_a_plain_fence_compiles_with_a_source_comment(self):
        # Given a guide with one ordinary kotlin fence
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), "# Title\n\n```kotlin\nimport a.B\n```\n")
            # When it is built
            files, skipped = build_guide(path, [], set())
        # Then one top-level file is produced, named for its source, with the source comment first
        self.assertEqual(len(files), 1)
        self.assertEqual(skipped, [])
        filename, content = files[0]
        self.assertEqual(filename, "sample_snippet1.kt")
        self.assertEqual(content.splitlines()[0], "// docs/guides/sample.md snippet 1")

    def test_no_compile_marker_excludes_the_fence_with_its_reason(self):
        # Given a fence preceded by a no-compile marker
        body = "<!-- no-compile: pseudo-code -->\n```kotlin\nthis is not kotlin\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built
            files, skipped = build_guide(path, [], set())
        # Then nothing compiles, and the reason travels with the skip
        self.assertEqual(files, [])
        self.assertEqual(len(skipped), 1)
        self.assertIn("pseudo-code", skipped[0])

    def test_empty_no_compile_reason_is_an_extractor_error(self):
        # Given a marker with no reason
        body = "<!-- no-compile: -->\n```kotlin\nval x = 1\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built
            # Then the extractor refuses to guess a reason silently
            with self.assertRaises(ExtractionError):
                build_guide(path, [], set())

    def test_wrap_fences_accumulate_into_one_narrative_in_order(self):
        # Given two statement-style fences, the second assuming a val the first declared
        body = "```kotlin\nval engine = build()\n```\n\ntext\n\n```kotlin\nengine.invalidate(request)\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built
            files, skipped = build_guide(path, [], set())
        # Then both land in one narrative file, in order, each under its own source marker
        self.assertEqual(skipped, [])
        self.assertEqual(len(files), 1)
        filename, content = files[0]
        self.assertEqual(filename, "sample_narrative.kt")
        first = content.index("snippet 1")
        second = content.index("snippet 2")
        self.assertLess(first, second)
        self.assertIn("val engine = build()", content)
        self.assertIn("engine.invalidate(request)", content)

    def test_rebound_name_is_isolated_in_run_and_does_not_leak_forward(self):
        # Given a fence rebinding a name the prelude/an earlier fence already bound, followed by one
        # that uses the name again
        body = "```kotlin\nval engine = alternativeBuild()\n```\n\ntext\n\n```kotlin\nengine.invalidate(request)\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built
            files, _ = build_guide(path, [], set())
        # Then the rebinding fence is wrapped alone in `run { }`
        content = files[0][1]
        self.assertIn("run {", content)
        self.assertIn("val engine = alternativeBuild()", content)
        # And the prelude's `engine` (not the isolated one) is what the second fence still sees —
        # i.e. the isolated fence's own name was never added to the visible set
        prelude_engine_line = next(line for line in content.splitlines() if "val engine = doc.samples.prelude" in line)
        self.assertIn(prelude_engine_line, content)

    def test_internal_duplicate_within_one_fence_is_an_extraction_error(self):
        # Given a fence declaring the same name twice within itself
        body = "```kotlin\nval engine = a()\nval engine = b()\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built
            # Then a single run{} cannot isolate the two declarations from each other, so this fails
            with self.assertRaises(ExtractionError):
                build_guide(path, [], set())

    def test_top_level_fence_colliding_with_a_reserved_name_gets_its_own_subpackage(self):
        # Given a top-level fence restating a real public type's name
        body = "```kotlin\nsealed class EnrichmentResult {\n}\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built with that name reserved
            files, _ = build_guide(path, [], {"EnrichmentResult"})
        # Then it is not written into the guide's shared package
        content = files[0][1]
        self.assertEqual(f"package {guide_package('sample')}.snippet1", content.splitlines()[1])

    def test_second_top_level_fence_colliding_with_the_first_gets_its_own_subpackage(self):
        # Given two top-level fences that each declare the same top-level name
        body = "```kotlin\nval engine = a()\nclass Foo\n```\n\ntext\n\n```kotlin\nval engine = b()\nclass Bar\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is built
            files, _ = build_guide(path, [], set())
        # Then the first stays in the shared package and the second is isolated from it
        first_content = files[0][1]
        second_content = files[1][1]
        self.assertEqual(f"package {guide_package('sample')}", first_content.splitlines()[1])
        self.assertEqual(f"package {guide_package('sample')}.snippet2", second_content.splitlines()[1])


class RunTest(unittest.TestCase):
    def test_run_clears_stale_output_before_writing(self):
        # Given an output directory holding a file from a since-removed fence
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            guides = root / "docs" / "guides"
            guides.mkdir(parents=True)
            (guides / "g.md").write_text("```kotlin\nimport a.B\n```\n", encoding="utf-8")
            out = root / "out"
            out.mkdir()
            (out / "stale.kt").write_text("// old\n", encoding="utf-8")
            # When run() runs
            compiled, skipped = run(root, out)
            # Then the stale file is gone and only the current fence's output remains
            self.assertEqual(compiled, 1)
            self.assertEqual(skipped, 0)
            self.assertFalse((out / "stale.kt").exists())
            self.assertTrue((out / "g_snippet1.kt").exists())


class PublicApiTest(unittest.TestCase):
    def write_api(self, root: Path) -> None:
        api_dir = root / "musicmeta-core" / "api"
        api_dir.mkdir(parents=True)
        (api_dir / "musicmeta-core.api").write_text(
            "public final class com/landofoz/musicmeta/EnrichmentEngine {\n}\n",
            encoding="utf-8",
        )

    def test_public_packages_reads_the_dotted_package_not_the_class(self):
        # Given an api file declaring one public class
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_api(root)
            # When public_packages runs
            packages = public_packages(root)
        # Then it reports the package
        self.assertEqual(packages, ["com.landofoz.musicmeta"])

    def test_public_simple_names_reads_the_bare_class_name(self):
        # Given the same api file
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_api(root)
            # When public_simple_names runs
            names = public_simple_names(root)
        # Then it reports the class name, not the package
        self.assertEqual(names, {"EnrichmentEngine"})


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
