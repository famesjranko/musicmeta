#!/usr/bin/env python3
"""Runnable self-check: `python3 test_build_release_notes.py` (no framework needed)."""

from pathlib import Path

from build_release_notes import MAX_LINE_CHARS, MAX_SECTION_CHARS, BuildError, build, extract_section

# A new-convention section: one line per change, rationale left in the issue.
GOOD = """# Changelog

## [Unreleased]

## [0.11.0] - 2026-07-23

Release automation — tagging, publishing and notes now run from one dispatched workflow.

### Added
- Dispatch-driven release pipeline, tag created last from the verified version (#42)

### Fixed
- README coordinates are rewritten by CI instead of by hand (#43)

## [0.10.1] - 2026-07-22

A throwing merge strategy no longer escapes `enrich()`.

### Fixed
- Guard consumer-supplied mergers and synthesizers (#28)
"""


def expect_error(fn, fragment: str) -> None:
    """Assert fn() raises BuildError whose message mentions `fragment`."""
    try:
        fn()
    except BuildError as e:
        assert fragment in str(e), f"expected {fragment!r} in error, got: {e}"
        return
    raise AssertionError(f"expected a BuildError mentioning {fragment!r}, none raised")


# --- extraction -----------------------------------------------------------------------------
section = extract_section(GOOD, "0.11.0")
assert section.startswith("Release automation"), section[:60]
assert "(#42)" in section and "(#43)" in section
# Stops at the next version heading rather than swallowing the rest of the file.
assert "0.10.1" not in section and "#28" not in section
# The heading line itself is not repeated — the GitHub release title already carries the version.
assert not section.startswith("## ")
assert extract_section(GOOD, "v0.11.0") == section, "a leading v must be accepted"

expect_error(lambda: extract_section(GOOD, "9.9.9"), "no '## [9.9.9]'")
expect_error(lambda: extract_section("## [1.0.0] - 2026\n\n## [0.9.0] - 2025\n", "1.0.0"), "is empty")

# --- assembly -------------------------------------------------------------------------------
body = build(GOOD, "0.11.0")
assert "Release automation" in body
for module in ("musicmeta-core", "musicmeta-okhttp", "musicmeta-android"):
    assert f'implementation("io.github.famesjranko:{module}:0.11.0")' in body, module
    assert f'implementation("com.github.famesjranko.musicmeta:{module}:v0.11.0")' in body, module
assert "compare/v0.10.1...v0.11.0" in body, "compare link must span the previous pinned release"
assert "img.shields.io" not in body and "jitpack.io/v/" not in body, "no floating badges"

# The oldest release has nothing to compare against, so the link is omitted rather than dangling.
assert "**Full Changelog**" not in build(GOOD, "0.10.1")

# --- caps -----------------------------------------------------------------------------------
too_long = GOOD.replace("### Added", "### Added\n" + "- padding line with some words in it (#1)\n" * 90)
expect_error(lambda: build(too_long, "0.11.0"), f"limit {MAX_SECTION_CHARS}")

one_fat_line = GOOD.replace(
    "- Dispatch-driven release pipeline, tag created last from the verified version (#42)",
    "- " + "x" * (MAX_LINE_CHARS + 1) + " (#42)",
)
expect_error(lambda: build(one_fat_line, "0.11.0"), f"limit {MAX_LINE_CHARS}")

# Exactly at the line cap is allowed; one over is not.
at_cap = GOOD.replace(
    "- Dispatch-driven release pipeline, tag created last from the verified version (#42)",
    "-" + "x" * (MAX_LINE_CHARS - 1),
)
build(at_cap, "0.11.0")

# --- the real CHANGELOG ---------------------------------------------------------------------
# 0.10.1 predates the convention and is ~6.6KB. It MUST fail — that wall is why the caps exist.
real = (Path(__file__).resolve().parent.parent.parent / "CHANGELOG.md").read_text(encoding="utf-8")
expect_error(lambda: build(real, "0.10.1"), "not release-note shaped")

print("build_release_notes: all checks passed")
