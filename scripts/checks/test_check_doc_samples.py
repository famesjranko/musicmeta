#!/usr/bin/env python3
"""Self-check for check_doc_samples.py.

Covers the classification rule (top-level vs. wrapped, and the two things that force a wrap even
past a `val`/`var` fast path), the opt-out marker (present, absent, empty-reason), and the two output
guarantees a consumer of `run()`/`extract_guide()` depends on: one file per compiled fence and a
first-line source comment. Run with: python3 test_check_doc_samples.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_doc_samples import (  # noqa: E402
    ExtractionError,
    classify,
    extract_guide,
    public_packages,
    run,
)


def fence(*body_lines: str) -> list[str]:
    return list(body_lines)


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


class ExtractGuideTest(unittest.TestCase):
    def write_guide(self, tmp: Path, body: str) -> Path:
        guides = tmp / "docs" / "guides"
        guides.mkdir(parents=True, exist_ok=True)
        path = guides / "sample.md"
        path.write_text(body, encoding="utf-8")
        return path

    def test_a_plain_fence_compiles_with_a_source_comment(self):
        # Given a guide with one ordinary kotlin fence
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), "# Title\n\n```kotlin\nval x = 1\n```\n")
            # When it is extracted
            compiled, skipped = extract_guide(path, [])
        # Then one file is produced, named for its source, with the source comment as its first line
        self.assertEqual(len(compiled), 1)
        self.assertEqual(len(skipped), 0)
        filename, content = compiled[0]
        self.assertEqual(filename, "sample_snippet1.kt")
        self.assertEqual(content.splitlines()[0], "// docs/guides/sample.md snippet 1")

    def test_no_compile_marker_excludes_the_fence_with_its_reason(self):
        # Given a fence preceded by a no-compile marker
        body = "<!-- no-compile: pseudo-code -->\n```kotlin\nthis is not kotlin\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is extracted
            compiled, skipped = extract_guide(path, [])
        # Then nothing compiles, and the reason travels with the skip
        self.assertEqual(compiled, [])
        self.assertEqual(len(skipped), 1)
        self.assertIn("pseudo-code", skipped[0])

    def test_empty_no_compile_reason_is_an_extractor_error(self):
        # Given a marker with no reason
        body = "<!-- no-compile: -->\n```kotlin\nval x = 1\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is extracted
            # Then the extractor refuses to guess a reason silently
            with self.assertRaises(ExtractionError):
                extract_guide(path, [])

    def test_two_fences_in_one_guide_get_independent_packages(self):
        # Given a guide with two fences
        body = "```kotlin\nval a = 1\n```\n\ntext\n\n```kotlin\nval b = 2\n```\n"
        with tempfile.TemporaryDirectory() as tmp:
            path = self.write_guide(Path(tmp), body)
            # When it is extracted
            compiled, _ = extract_guide(path, [])
        # Then each file carries a package unique to its own snippet index
        packages = [
            next(line for line in content.splitlines() if line.startswith("package ")) for _, content in compiled
        ]
        self.assertEqual(len(set(packages)), 2)


class RunTest(unittest.TestCase):
    def test_run_clears_stale_output_before_writing(self):
        # Given an output directory holding a file from a since-removed fence
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            guides = root / "docs" / "guides"
            guides.mkdir(parents=True)
            (guides / "g.md").write_text("```kotlin\nval a = 1\n```\n", encoding="utf-8")
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


class PublicPackagesTest(unittest.TestCase):
    def test_reads_packages_from_api_files(self):
        # Given an api file declaring one public class
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            api_dir = root / "musicmeta-core" / "api"
            api_dir.mkdir(parents=True)
            (api_dir / "musicmeta-core.api").write_text(
                "public final class com/landofoz/musicmeta/EnrichmentEngine {\n}\n",
                encoding="utf-8",
            )
            # When public_packages runs
            packages = public_packages(root)
        # Then it reports the package, dotted, not the class
        self.assertEqual(packages, ["com.landofoz.musicmeta"])


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
