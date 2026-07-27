#!/usr/bin/env python3
"""Runnable self-check: `python3 test_build_release_notes.py` (no framework needed)."""

import tempfile
from pathlib import Path

from build_release_notes import (
    MAX_LINE_CHARS,
    MAX_SECTION_CHARS,
    BuildError,
    build,
    check_unreleased,
    extract_section,
    main,
    released_versions,
    section_body,
)

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
padding = "- padding line with some words in it (#1)\n"
too_long = GOOD.replace("### Added", "### Added\n" + padding * (MAX_SECTION_CHARS // len(padding) + 5))
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

# --- the [Unreleased] caps gate -------------------------------------------------------------
# Reaching an unpinned section must not put it in the released-version list: that list drives the
# compare link, so `Unreleased` leaking in ships `compare/vUnreleased...v0.11.0` in a real release.
UNRELEASED = GOOD.replace("## [Unreleased]\n", "## [Unreleased]\n\n### Fixed\n- Something small (#44)\n")
assert released_versions(UNRELEASED) == ["0.11.0", "0.10.1"], released_versions(UNRELEASED)
assert "compare/v0.10.1...v0.11.0" in build(UNRELEASED, "0.11.0"), "the compare link must be unaffected"

assert section_body(UNRELEASED, "Unreleased") == "### Fixed\n- Something small (#44)"
check_unreleased(UNRELEASED)

# An empty [Unreleased] passes the gate. pin_release.py opens exactly this immediately after
# pinning a release, so failing here would turn every release branch red — GOOD has that shape,
# and so does the real post-pin output.
check_unreleased(GOOD)
check_unreleased("# Changelog\n\n## [Unreleased]\n\n## [0.11.0] - 2026-07-26\n\nNotes.\n")
# But the heading must exist: pin_release.py needs it, and a caps gate on a section that is not
# there is a gate that silently checks nothing.
expect_error(lambda: check_unreleased("# Changelog\n\n## [0.11.0] - 2026\n\nNotes.\n"), "no '## [Unreleased]'")
# `build()` still refuses an unpinned version rather than reaching `versions.index("Unreleased")`,
# and still refuses a pinned-but-empty section.
expect_error(lambda: build(UNRELEASED, "Unreleased"), "no '## [Unreleased]'")
expect_error(lambda: build("## [1.0.0] - 2026\n\n## [0.9.0] - 2025\n", "1.0.0"), "is empty")
fat = UNRELEASED.replace("- Something small (#44)", "- " + "x" * (MAX_LINE_CHARS + 1))
expect_error(lambda: check_unreleased(fat), f"limit {MAX_LINE_CHARS}")
bulky = UNRELEASED.replace("- Something small (#44)", padding * (MAX_SECTION_CHARS // len(padding) + 5))
expect_error(lambda: check_unreleased(bulky), f"limit {MAX_SECTION_CHARS}")

# The two heading matchers must agree: released_versions() accepts `##  [x]` via `\s+`, so
# section_body() has to find that body rather than report the version missing.
ODD_SPACING = GOOD.replace("## [0.11.0]", "##  [0.11.0]")
assert released_versions(ODD_SPACING) == ["0.11.0", "0.10.1"]
assert section_body(ODD_SPACING, "0.11.0").startswith("Release automation")

# --- the CLI, which is what ./check actually runs ---------------------------------------------
# check_unreleased() passing is not evidence the gate passes: `./check` reads an exit code from
# main(), and the two only agree if something asserts it.
tmp = Path(tempfile.mkdtemp()) / "CHANGELOG.md"

tmp.write_text(UNRELEASED, encoding="utf-8")
assert main(["Unreleased", "--changelog", str(tmp)]) == 0

tmp.write_text(fat, encoding="utf-8")
assert main(["Unreleased", "--changelog", str(tmp)]) == 2, "an over-cap line must exit 2"

tmp.write_text("# Changelog\n\n## [0.11.0] - 2026\n\nNotes.\n", encoding="utf-8")
assert main(["Unreleased", "--changelog", str(tmp)]) == 1, "a missing section must exit 1, not 2"

# --- the real CHANGELOG ---------------------------------------------------------------------
real = (Path(__file__).resolve().parent.parent.parent / "CHANGELOG.md").read_text(encoding="utf-8")

# Released sections stay as published on GitHub, and 0.10.x and earlier predate the 200-char
# per-entry cap (#104), so the forward guard against a wall-of-text paste is [Unreleased] — the
# section ./check gates on every commit.
check_unreleased(real)

# 0.9.2 predates every cap — a 825-char line. It MUST fail: a cap that has never fired on real
# prose is a cap nobody has tested, and every fixture above is one we wrote to fit.
expect_error(lambda: build(real, "0.9.2"), "not release-note shaped")

print("build_release_notes: all checks passed")
