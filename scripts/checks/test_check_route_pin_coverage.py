#!/usr/bin/env python3
"""Self-check for check_route_pin_coverage.py.

A gate nobody has watched fail is not a gate, so every path a route can take is proved here: tied to
a pin by name, tied by the builder its pin calls, unpinned, unpinned but allowlisted. The shapes that
decide whether this check is worth having get their own cases — a route hidden behind a private fetch
helper, an expression body, an `httpClient` call outside an api client, and one no route reaches —
because each is a way the check could go blind and report nothing, which reads as a clean tree. The
two lists are proved to reject their own stale entries, and the empty tree is proved to fail rather
than pass on nothing. Run with: python3 test_check_route_pin_coverage.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_route_pin_coverage import NO_PROVIDERS_FINDING, PROVIDER_ROOT, run  # noqa: E402

PIN_BY_NAME = """
    companion object {
        val SCHEMA_PIN_TARGETS = listOf(
            SchemaTarget(
                route = "getArtist",
                url = "https://example.test/artist",
            ),
        )
    }
"""

PIN_BY_BUILDER = """
    companion object {
        fun artistUrl(id: String): String = "https://example.test/artist/$id"

        val SCHEMA_PIN_TARGETS = listOf(
            SchemaTarget(
                route = "artist lookup",
                url = artistUrl("42"),
            ),
        )
    }
"""


def api(*, routes: str, pins: str = "") -> str:
    return f"package a\n\ninternal class FooApi(private val httpClient: HttpClient) {{\n{routes}\n{pins}\n}}\n"


class RoutePinCoverageTest(unittest.TestCase):
    def findings_for(
        self,
        files: dict[str, str],
        *,
        allowlist: dict[str, str] | None = None,
        call_sites: dict[str, str] | None = None,
    ) -> list[str]:
        """Findings for a tree holding just these files under a fresh temp root."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, body in files.items():
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")
            return run(root, allowlist=allowlist or {}, call_sites=call_sites or {})

    def api_findings(self, source: str, **kwargs: object) -> list[str]:
        """Findings for a tree holding one provider whose api client is `source`."""
        return self.findings_for({f"{PROVIDER_ROOT}/foo/FooApi.kt": source}, **kwargs)  # type: ignore[arg-type]

    # --- the four paths a route can take ---

    def test_route_named_by_a_pin_passes(self):
        # Given - a route whose name is a pin's `route`
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? {
        return httpClient.fetchJsonResult("https://example.test/artist/$id").body
    }
""",
            pins=PIN_BY_NAME,
        )
        # When - the check reads it
        findings = self.api_findings(source)
        # Then - nothing is reported
        self.assertEqual(findings, [])

    def test_route_calling_the_builder_its_pin_calls_passes(self):
        # Given - a route that builds its URL with the builder the pin names
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? {
        return httpClient.fetchJsonResult(artistUrl(id)).body
    }
""",
            pins=PIN_BY_BUILDER,
        )
        # When - the check reads it
        findings = self.api_findings(source)
        # Then - nothing is reported
        self.assertEqual(findings, [])

    def test_route_with_no_pin_is_reported(self):
        # Given - a route with no pin of any kind
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? {
        return httpClient.fetchJsonResult("https://example.test/artist/$id").body
    }
"""
        )
        # When - the check reads it
        findings = self.api_findings(source)
        # Then - it is named, once, at its own line
        self.assertEqual(len(findings), 1)
        self.assertIn("`foo/getArtist`", findings[0])
        self.assertIn("FooApi.kt,line=5", findings[0])

    def test_unpinned_route_on_the_allowlist_passes(self):
        # Given - the same unpinned route, allowlisted with a reason
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? {
        return httpClient.fetchJsonResult("https://example.test/artist/$id").body
    }
"""
        )
        # When - the check reads it against an allowlist naming it
        findings = self.api_findings(source, allowlist={"foo/getArtist": "no reason to pin a stub"})
        # Then - nothing is reported
        self.assertEqual(findings, [])

    # --- the shapes that decide whether the check can see anything ---

    def test_route_reaching_http_through_a_private_helper_is_reported(self):
        # Given - a provider whose routes all go through one private fetch helper
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? = fetch("https://example.test/artist/$id")

    suspend fun getAlbum(id: String): String? = fetch("https://example.test/album/$id")

    private suspend fun fetch(url: String): String? {
        return httpClient.fetchJsonResult(url).body
    }
"""
        )
        # When - the check reads it
        findings = self.api_findings(source)
        # Then - both public routes are reported, and the helper itself is not
        self.assertEqual(len(findings), 2)
        self.assertIn("`foo/getArtist`", findings[0])
        self.assertIn("`foo/getAlbum`", findings[1])

    def test_expression_bodied_route_is_reported(self):
        # Given - a route whose whole body is on its header line
        source = api(routes="""    suspend fun getArtist(id: String) = httpClient.fetchJsonResult(id).body\n""")
        # When - the check reads it
        findings = self.api_findings(source)
        # Then - it is found, not lost with the header
        self.assertEqual(len(findings), 1)
        self.assertIn("`foo/getArtist`", findings[0])

    def test_private_suspend_fun_is_not_a_route(self):
        # Given - a file whose only http caller is private, and reached by nothing
        source = api(
            routes="""
    private suspend fun fetch(url: String): String? {
        return httpClient.fetchJsonResult(url).body
    }
"""
        )
        # When - the check reads it
        findings = self.api_findings(source)
        # Then - it is reported as a call site no route reaches, not as an unpinned route
        self.assertEqual(len(findings), 1)
        self.assertIn("no route in its own file reaches it", findings[0])
        self.assertIn("`foo/FooApi.kt#fetch`", findings[0])

    def test_route_in_a_nested_package_is_reported(self):
        # Given - an api client one package below the provider directory
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? {
        return httpClient.fetchJsonResult("https://example.test/artist/$id").body
    }
"""
        )
        # When - the check reads it
        findings = self.findings_for({f"{PROVIDER_ROOT}/foo/inner/BarApi.kt": source})
        # Then - the nesting does not take the route out of the enumeration
        self.assertEqual(len(findings), 1)
        self.assertIn("`inner/getArtist`", findings[0])

    def test_http_call_outside_an_api_client_is_reported(self):
        # Given - a provider that asks an upstream from its provider class
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooApi.kt": api(routes=""),
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": (
                    "package a\n\nclass FooProvider {\n"
                    "    private suspend fun resolve(id: String): String? {\n"
                    "        return httpClient.fetchJsonResult(id).body\n"
                    "    }\n}\n"
                ),
            }
        )
        # Then - the call site is named, with the file it sits in
        self.assertEqual(len(findings), 1)
        self.assertIn("outside an `*Api.kt`", findings[0])
        self.assertIn("`foo/FooProvider.kt#resolve`", findings[0])

    def test_allowlisted_call_site_outside_an_api_client_passes(self):
        # Given - the same call, named in the call-site list
        findings = self.findings_for(
            {
                f"{PROVIDER_ROOT}/foo/FooApi.kt": api(routes=""),
                f"{PROVIDER_ROOT}/foo/FooProvider.kt": (
                    "package a\n\nclass FooProvider {\n"
                    "    private suspend fun resolve(id: String): String? {\n"
                    "        return httpClient.fetchJsonResult(id).body\n"
                    "    }\n}\n"
                ),
            },
            call_sites={"foo/FooProvider.kt#resolve": "a stub, not a route"},
        )
        # Then - nothing is reported
        self.assertEqual(findings, [])

    # --- both lists reject what they have outlived ---

    def test_allowlist_entry_for_a_route_that_is_not_unpinned_is_reported(self):
        # Given - an allowlist naming a route this tree does not have unpinned
        source = api(
            routes="""
    suspend fun getArtist(id: String): String? {
        return httpClient.fetchJsonResult("https://example.test/artist/$id").body
    }
""",
            pins=PIN_BY_NAME,
        )
        # When - the check reads it
        findings = self.api_findings(source, allowlist={"foo/getArtist": "pinned since"})
        # Then - the stale line is reported
        self.assertEqual(len(findings), 1)
        self.assertIn("is not an unpinned route", findings[0])

    def test_call_site_entry_for_a_call_that_does_not_exist_is_reported(self):
        # Given - a call-site list naming a call this tree does not make
        findings = self.api_findings(api(routes=""), call_sites={"foo/FooProvider.kt#gone": "moved"})
        # Then - the stale line is reported
        self.assertEqual(len(findings), 1)
        self.assertIn("no such", findings[0])

    # --- the way the check itself can scan nothing ---

    def test_a_tree_with_no_api_client_is_reported(self):
        # Given - a tree with no provider api client at all
        findings = self.findings_for({"README.md": "nothing here\n"})
        # Then - the check says it scanned nothing rather than passing on it
        self.assertEqual(findings, [NO_PROVIDERS_FINDING])


if __name__ == "__main__":
    unittest.main()
