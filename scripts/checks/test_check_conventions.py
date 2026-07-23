#!/usr/bin/env python3
"""Self-check for check_conventions.py.

A gate nobody has watched fail is not a gate, so every rule here is proved to fire on the exact
violation it exists to catch, and proved not to fire on the thing most likely to be mistaken for
one. Run with: python3 test_check_conventions.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_conventions import (  # noqa: E402
    GRANDFATHERED_LONG_FILES,
    MAX_FILE_LINES,
    run,
    strip_noise,
)


class ConventionsTest(unittest.TestCase):
    def write(self, root: Path, rel: str, body: str) -> None:
        """Given a repo root, place a Kotlin source at a module-relative path."""
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def findings_for(self, rel: str, body: str) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, rel, body)
            return run(root)

    # --- no `!!` ---

    def test_double_bang_in_main_source_is_reported(self):
        # Given a main source dereferencing a nullable with the not-null assertion
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the conventions check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it fails and the message tells the author what to do instead
        self.assertEqual(len(findings), 1)
        self.assertIn("`!!` is banned", findings[0])
        self.assertIn("requireNotNull", findings[0])
        self.assertIn("line=3", findings[0])

    def test_double_bang_inside_a_string_or_comment_is_not_reported(self):
        # Given `!!` appearing only in a string literal and a comment
        body = 'package a\n\n// wow!!\nval s = "loud!!"\nval t = """also!!"""\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported, because neither is a not-null assertion
        self.assertEqual(findings, [])

    def test_identity_operator_is_not_a_double_bang(self):
        # Given the `!==` identity operator, which contains no `!!` at all
        body = "package a\n\nval b = x !== y\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported
        self.assertEqual(findings, [])

    def test_double_bang_glued_to_an_equality_operator_is_reported(self):
        # Given `u!!==v`, valid Kotlin that the old `!!=` guard skipped
        body = "package a\n\nval b = u!!==v\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it is reported — it is a genuine not-null assertion
        self.assertEqual(len(findings), 1)

    def test_repeated_negation_is_a_known_false_positive(self):
        # Given repeated negation, which is not a not-null assertion
        body = "package a\n\nval c = !!!flag\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it IS reported. Accepted deliberately: distinguishing it needs a real lexer, it is
        # rare in Kotlin, and a loud false positive beats the silent false negative that avoiding
        # it would reintroduce.
        self.assertEqual(len(findings), 1)

    # --- strip_noise holes found in review of #47. Every one was a silent false negative. ---

    def test_double_bang_after_a_url_string_is_reported(self):
        # Given a `!!` on the same line as a URL, whose "//" previously looked like a comment
        body = 'package a\n\nfun f(u: String?) = "https://example.com/" + u!!.trim()\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it is reported — this is the shape almost every provider line in this repo takes
        self.assertEqual(len(findings), 1, "a '//' inside a string must not blank the rest of the line")

    def test_double_bang_after_a_string_containing_block_comment_open_is_reported(self):
        # Given a string holding "/*", which previously blanked everything to the next "*/"
        body = 'package a\n\nval glob = "src/*"\nfun g(u: String?) = u!!.trim()\nval o = "*/main"\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` between them is still seen
        self.assertEqual(len(findings), 1)

    def test_double_bang_after_a_comment_containing_triple_quote_is_reported(self):
        # Given a line comment mentioning triple quotes, which previously opened a fake raw string
        body = 'package a\n\n// prose about """ quoting\nfun g(u: String?) = u!!.trim()\n// close """\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the code between the two comments is still scanned
        self.assertEqual(len(findings), 1)

    def test_double_bang_inside_a_string_template_is_reported(self):
        # Given `!!` inside `${...}`, which is code even though it sits within a string literal
        body = 'package a\n\nfun g(u: String?) = "name is ${u!!.trim()}"\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then it is reported — interpolation is where `!!` most often hides
        self.assertEqual(len(findings), 1)

    def test_nested_braces_in_a_template_do_not_swallow_the_closing_brace(self):
        # Given a template whose expression contains its own braces
        body = 'package a\n\nfun g(u: List<String>?) = "x ${u!!.map { it }} y!!z"\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` in the expression is caught and the trailing literal "y!!z" is not
        self.assertEqual(len(findings), 1)

    def test_literal_text_around_a_template_is_still_blanked(self):
        # Given `!!` in the literal part of an interpolated string, which is not code
        body = 'package a\n\nval s = "loud!! ${name} still loud!!"\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported — only the ${...} span counts as code
        self.assertEqual(findings, [])

    # --- nesting holes found in the second review of #47. Regex could not express these. ---

    def test_double_bang_survives_a_nested_string_inside_a_template(self):
        # Given the shape WikidataApi.kt already ships: a quoted argument inside ${...}
        body = 'package a\n\nfun url(id: String?) = "&p=${URLEncoder.encode(id!!, "UTF-8")}&f=json"\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` is reported — the nested quote must not truncate the outer literal
        self.assertEqual(len(findings), 1)

    def test_apostrophe_in_a_nested_string_does_not_swallow_later_lines(self):
        # Given an apostrophe inside a nested string, which previously leaked into code position
        # and opened a char literal spanning the lines after it
        body = (
            "package a\n\n"
            'fun a(m: Map<String, String>) = "${m["it\'s"]}"\n'
            "fun b(u: String?) = u!!.trim()\n"
            'fun c(m: Map<String, String>) = "${m["don\'t"]}"\n'
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` between them is still seen
        self.assertEqual(len(findings), 1)
        self.assertIn("line=4", findings[0])

    def test_block_comment_open_in_a_nested_string_does_not_swallow_later_lines(self):
        # Given "/*" inside a nested string, the same leak with unbounded reach
        body = (
            "package a\n\n"
            'fun a(m: Map<String, String>) = "${m["/*"]}"\n'
            "fun b(u: String?) = u!!.trim()\n"
            'fun c(m: Map<String, String>) = "${m["*/"]}"\n'
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` is still reported
        self.assertEqual(len(findings), 1)

    def test_nested_block_comments_are_fully_commented_out(self):
        # Given a commented-out region containing KDoc — Kotlin block comments nest, unlike Java's
        body = "package a\n\n/* disabled\n/** Old doc. */\nfun old(u: String?) = u!!.trim()\n*/\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported — a lazy match stopped at the first `*/` and scanned the rest
        self.assertEqual(findings, [])

    def test_escaped_dollar_is_not_a_template(self):
        # Given `\$` which is a literal dollar in Kotlin, not the start of interpolation
        body = 'package a\n\nval s = "\\${a!!b}"\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported — the braces are literal text
        self.assertEqual(findings, [])

    def test_raw_string_ignores_backslash_escapes(self):
        # Given a raw string ending in a backslash, where escape processing would misread the end
        body = 'package a\n\nval r = """back\\"""\nfun g(u: String?) = u!!.trim()\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the code after the raw string is still scanned
        self.assertEqual(len(findings), 1)

    def test_strip_noise_is_length_preserving_on_pathological_input(self):
        # Given constructs that previously desynchronised the scanner
        for source in (
            'val a = "${m["x"]}"\n',
            "/* /* nested */ */\n",
            'val r = """raw ${x["y"]} end"""\n',
            'val u = "unterminated ${\n',
            "val c = '\\''\n",
        ):
            # When noise is blanked
            stripped = strip_noise(source)
            # Then length and newline positions are identical, or every later line number is wrong
            self.assertEqual(len(stripped), len(source), repr(source))
            self.assertEqual(
                [i for i, c in enumerate(source) if c == "\n"],
                [i for i, c in enumerate(stripped) if c == "\n"],
                repr(source),
            )

    # --- lexical rules found missing in the third review, each confirmed against kotlinc ---

    def test_raw_string_ending_in_a_quote_does_not_leak(self):
        # Given a raw string whose content ends with a quote, so it closes on a run of four.
        # Kotlin closes on the LAST three; closing on the first left a stray quote in code
        # position that opened a normal string and swallowed the rest of the file.
        body = 'package a\n\nval banner = """he said "hi""""\nfun boom(u: String?) = u!!.trim()\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` after it is still reported
        self.assertEqual(len(findings), 1)
        self.assertIn("line=4", findings[0])

    def test_raw_string_with_a_long_quote_run_does_not_leak(self):
        # Given a seven-quote run, which also terminates on its last three
        body = 'package a\n\nval s = """x""""""\nfun boom(u: String?) = u!!.trim()\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` is reported
        self.assertEqual(len(findings), 1)

    def test_empty_raw_string_still_closes(self):
        # Given an empty raw string — six quotes, which must not be read as one open plus content
        body = 'package a\n\nval s = """"""\nfun boom(u: String?) = u!!.trim()\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` after it is reported
        self.assertEqual(len(findings), 1)

    def test_backtick_identifier_with_an_apostrophe_does_not_leak(self):
        # Given a backtick-escaped name containing `'`, which previously opened a char literal
        # that ran to EOF
        body = "package a\n\nfun `client's id`() = 1\nfun boom(u: String?) = u!!.trim()\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` after it is still reported
        self.assertEqual(len(findings), 1)
        self.assertIn("line=4", findings[0])

    def test_backtick_identifier_with_a_quote_does_not_leak(self):
        # Given a backtick-escaped name containing an odd `"` — legal Kotlin, verified with kotlinc
        body = 'package a\n\nfun `26" wide`() = 1\nfun boom(u: String?) = u!!.trim()\n'
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the `!!` is reported
        self.assertEqual(len(findings), 1)

    def test_double_bang_inside_a_backtick_identifier_is_not_reported(self):
        # Given `!!` inside an escaped name, which is part of the name and not an assertion
        body = "package a\n\nfun `not!!really`() = 1\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then nothing is reported
        self.assertEqual(findings, [])

    def test_form_feed_does_not_shift_reported_line_numbers(self):
        # Given a form feed, which is valid Kotlin whitespace but which splitlines() counts as a
        # line break while GitHub's annotations count only \n
        body = "package a\n\nval x = 1\x0c\nfun g(u: String?) = u!!.trim()\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/A.kt", body)
        # Then the reported line matches \n-counting, which is what the annotation resolves against
        self.assertEqual(len(findings), 1)
        self.assertIn("line=4", findings[0])

    def test_root_given_as_dot_still_excludes_demo(self):
        # Given --root passed as a relative path, where prefix matching previously failed
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "demo/src/main/kotlin/F.kt", "package a\n\nfun f(x: String?) = x!!\n")
            cwd = Path.cwd()
            try:
                import os

                os.chdir(root)
                # When the check runs with a relative root
                findings = run(Path(".").resolve())
            finally:
                os.chdir(cwd)
        # Then demo/ is still excluded
        self.assertEqual(findings, [])

    def test_test_sources_are_not_scanned(self):
        # Given a test source using `!!`, which is allowed there
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/test/kotlin/A.kt", body)
        # Then it is ignored, because the rule is scoped to main sources
        self.assertEqual(findings, [])

    def test_demo_is_not_scanned(self):
        # Given the demo consumer canary breaking a house rule
        body = "package a\n\nfun f(x: String?) = x!!.length\n"
        # When the check runs
        findings = self.findings_for("demo/src/main/kotlin/A.kt", body)
        # Then it is ignored, because demo compiles as an external consumer would
        self.assertEqual(findings, [])

    # --- @Serializable placement ---

    def test_serializable_on_a_provider_model_is_reported(self):
        # Given @Serializable applied to a provider's internal model
        body = "package a\n\n@Serializable\ndata class DeezerAlbum(val id: Long)\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/provider/deezer/M.kt", body)
        # Then it fails and points at the payload types that should carry it instead
        self.assertEqual(len(findings), 1)
        self.assertIn("@Serializable does not belong", findings[0])
        self.assertIn("EnrichmentData", findings[0])

    def test_serializable_on_an_http_type_is_reported(self):
        # Given @Serializable on an http/ infrastructure type
        body = "package a\n\n@Serializable\nclass HttpThing\n"
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/http/T.kt", body)
        # Then it is reported for the same reason
        self.assertEqual(len(findings), 1)

    def test_serializable_on_a_public_payload_type_is_allowed(self):
        # Given @Serializable on a top-level public payload type, which is its intended home
        body = "package a\n\n@Serializable\ndata class EnrichmentIdentifiers(val mbid: String?)\n"
        # When the check runs
        findings = self.findings_for(
            "musicmeta-core/src/main/kotlin/com/landofoz/musicmeta/EnrichmentIdentifiers.kt", body
        )
        # Then nothing is reported
        self.assertEqual(findings, [])

    # --- file length ---

    def test_file_over_the_cap_is_reported(self):
        # Given a main source one line past the cap
        body = "package a\n" + "// filler\n" * MAX_FILE_LINES
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/Long.kt", body)
        # Then it fails and the message says to split rather than shave
        self.assertEqual(len(findings), 1)
        self.assertIn("Split it", findings[0])

    def test_file_exactly_at_the_cap_is_allowed(self):
        # Given a main source exactly at the cap — the boundary the rule must not misjudge
        body = "// filler\n" * MAX_FILE_LINES
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/Exact.kt", body)
        # Then it passes, because the cap is a maximum and not an exclusive bound
        self.assertEqual(findings, [])

    def test_grandfathered_file_is_not_reported(self):
        # Given one of the four files that predate the rule, still over the cap
        rel = sorted(GRANDFATHERED_LONG_FILES)[0]
        body = "// filler\n" * (MAX_FILE_LINES + 50)
        # When the check runs
        findings = self.findings_for(rel, body)
        # Then it passes, because the list exists to let the gate ship green
        self.assertEqual(findings, [])

    # --- cancellation must not become an Error (#53) ---

    def test_broad_catch_producing_an_error_is_reported(self):
        # Given the shape that shipped: a broad catch building an Error result, no rethrow
        body = (
            "suspend fun f(): EnrichmentResult = try {\n"
            "    call()\n"
            "} catch (e: Exception) {\n"
            '    EnrichmentResult.Error(type, id, e.message ?: "x", e)\n'
            "}\n"
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/P.kt", body)
        # Then it fails, and the message names both sanctioned fixes
        self.assertEqual(len(findings), 1)
        self.assertIn("circuit-breaker failure", findings[0])
        self.assertIn("mapError", findings[0])

    def test_cancellation_rethrow_before_the_broad_catch_is_accepted(self):
        # Given the ProviderChain fix: rethrow first, then the broad catch
        body = (
            "suspend fun f(): EnrichmentResult = try {\n"
            "    call()\n"
            "} catch (e: CancellationException) {\n"
            "    throw e\n"
            "} catch (e: Exception) {\n"
            '    EnrichmentResult.Error(type, id, e.message ?: "x", e)\n'
            "}\n"
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/P.kt", body)
        # Then it passes — the cancellation never reaches the broad clause
        self.assertEqual(findings, [])

    def test_delegating_to_map_error_is_accepted(self):
        # Given the provider fix: mapError owns the rethrow, so its callers need no guard
        body = (
            "suspend fun f(): EnrichmentResult = try {\n"
            "    call()\n"
            "} catch (e: Exception) {\n"
            "    mapError(type, e)\n"
            "}\n"
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/P.kt", body)
        # Then it passes
        self.assertEqual(findings, [])

    def test_broad_catch_that_only_logs_is_not_reported(self):
        # Given a broad catch that returns a fallback without producing an Error. Cancellation
        # re-asserts at the next suspension point, so this is not the bug and must not be flagged
        # — several such sites are deliberate and commented in the engine.
        body = (
            "suspend fun f(): List<String> = try {\n"
            "    call()\n"
            "} catch (e: Exception) {\n"
            '    logger.warn(TAG, "failed", e)\n'
            "    emptyList()\n"
            "}\n"
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/P.kt", body)
        # Then it passes, because the rule tests what the catch produces, not that it is broad
        self.assertEqual(findings, [])

    def test_a_specific_catch_producing_an_error_is_not_reported(self):
        # Given IOException — narrow, and cannot capture a CancellationException at all
        body = (
            "suspend fun f(): EnrichmentResult = try {\n"
            "    call()\n"
            "} catch (e: IOException) {\n"
            '    EnrichmentResult.Error(type, id, e.message ?: "x", e)\n'
            "}\n"
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/P.kt", body)
        # Then it passes
        self.assertEqual(findings, [])

    def test_error_inside_a_comment_does_not_trigger_the_rule(self):
        # Given the Error construction commented out — the mask must hide it, as for every rule
        body = (
            "suspend fun f(): String = try {\n"
            "    call()\n"
            "} catch (e: Exception) {\n"
            '    // EnrichmentResult.Error(type, id, "x", e)\n'
            '    ""\n'
            "}\n"
        )
        # When the check runs
        findings = self.findings_for("musicmeta-core/src/main/kotlin/P.kt", body)
        # Then it passes
        self.assertEqual(findings, [])

    # --- noise stripping ---

    def test_strip_noise_preserves_line_numbers(self):
        # Given a multi-line comment and a multi-line string
        source = 'a\n/* two\nlines */\nb\n"""x\ny"""\nc\n'
        # When noise is blanked out
        stripped = strip_noise(source)
        # Then the line count is unchanged, so reported line numbers still match the file
        self.assertEqual(len(stripped.splitlines()), len(source.splitlines()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
