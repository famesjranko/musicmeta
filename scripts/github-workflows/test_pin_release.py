#!/usr/bin/env python3
"""Runnable self-check: `python3 test_pin_release.py` (no framework needed)."""

from pathlib import Path

from build_release_notes import extract_section, released_versions
from pin_release import PinError, pin_changelog, pin_guides, pin_roadmap

BEFORE = """# Changelog

## [Unreleased]

Docs and CI only.

### Added
- A thing (#42)

## [0.10.1] - 2026-07-22

### Fixed
- An older thing (#28)
"""


def expect_error(fn, fragment: str) -> None:
    try:
        fn()
    except PinError as e:
        assert fragment in str(e), f"expected {fragment!r} in error, got: {e}"
        return
    raise AssertionError(f"expected a PinError mentioning {fragment!r}, none raised")


# --- the pin ------------------------------------------------------------------------------------
after = pin_changelog(BEFORE, "0.11.0", "2026-07-23")
assert "## [0.11.0] - 2026-07-23" in after
# A fresh empty [Unreleased] is opened above it — ipcamera leaves this to the next contributor,
# which is how a release's changes end up appended to the previous version's section.
assert after.index("## [Unreleased]") < after.index("## [0.11.0]")
# The content moves under the new version, and the new [Unreleased] is genuinely empty.
assert extract_section(after, "0.11.0").startswith("Docs and CI only.")
assert "A thing (#42)" in extract_section(after, "0.11.0")
unreleased = after[after.index("## [Unreleased]") + len("## [Unreleased]") : after.index("## [0.11.0]")]
assert not unreleased.strip(), f"new [Unreleased] should be empty, got {unreleased!r}"
# Older sections are untouched.
assert "## [0.10.1] - 2026-07-22" in after and "An older thing (#28)" in after

# Idempotence guard: pinning twice would otherwise create a second 0.11.0 section.
expect_error(lambda: pin_changelog(after, "0.11.0", "2026-07-24"), "already has a '## [0.11.0]'")

# --- refusals -----------------------------------------------------------------------------------
expect_error(
    lambda: pin_changelog("# Changelog\n\n## [0.10.1] - 2026\n", "0.11.0"[:], "2026-07-23"), "no '## [Unreleased]'"
)
expect_error(
    lambda: pin_changelog("# Changelog\n\n## [Unreleased]\n\n## [0.10.1] - 2026\n", "0.11.0", "2026-07-23"), "empty"
)
# Whitespace-only is still empty.
expect_error(lambda: pin_changelog("## [Unreleased]\n\n   \n\n## [0.10.1] - 2026\n", "0.11.0", "2026-07-23"), "empty")
# An [Unreleased] with no following version heading (first ever release) still pins.
assert "## [1.0.0] - 2026-07-23" in pin_changelog("# C\n\n## [Unreleased]\n\n- first (#1)\n", "1.0.0", "2026-07-23")

# --- a break cannot ship in a patch ---------------------------------------------------------------
BREAKS = """# Changelog

## [Unreleased]

### Breaking Changes
- Something narrowed

## [0.10.1] - 2026-07-22
"""
expect_error(lambda: pin_changelog(BREAKS, "0.10.2", "2026-07-23"), "cannot be a patch release over 0.10.1")
# The same content pins fine as a minor, and as a major.
assert "## [0.11.0]" in pin_changelog(BREAKS, "0.11.0", "2026-07-23")
assert "## [1.0.0]" in pin_changelog(BREAKS, "1.0.0", "2026-07-23")
# A patch with no Breaking Changes section is still allowed.
assert "## [0.10.2]" in pin_changelog(BREAKS.replace("### Breaking Changes", "### Fixed"), "0.10.2", "2026-07-23")
# The heading only counts inside [Unreleased] — an older section's break must not block a patch.
OLD_BREAK = "# C\n\n## [Unreleased]\n\n### Fixed\n- x\n\n## [0.10.1] - 2026\n\n### Breaking Changes\n- old\n"
assert "## [0.10.2]" in pin_changelog(OLD_BREAK, "0.10.2", "2026-07-23")

# --- roadmap ------------------------------------------------------------------------------------
assert pin_roadmap("## Where We Are (v0.10.1)\n\ntext\n", "0.11.0") == "## Where We Are (v0.11.0)\n\ntext\n"
# Only the first, and a missing heading is not an error.
assert pin_roadmap("no heading here\n", "0.11.0") == "no heading here\n"

# The prose under the heading names the version too, and moving only the heading is what let the
# ROADMAP tell readers 0.10.1 was current for three weeks after 0.11.0 shipped.
BLOCK = (
    "## Where We Are (v0.10.1)\n\n"
    "v0.10.1 is published to Maven Central and JitPack. Everything below the *Unreleased* block\n"
    "has shipped.\n\n"
    "### Unreleased — lands in the next release\n\n"
    "The published 0.10.1 artifact carries none of it.\n\n"
    "### Current Coverage\n\n"
    "| GENRE_DISCOVERY | **v0.6.0** — static taxonomy |\n"
)
pinned_block = pin_roadmap(BLOCK, "0.11.0")
assert "## Where We Are (v0.11.0)" in pinned_block
assert "v0.11.0 is published to Maven Central" in pinned_block, "the prose moves with the heading"
assert "The published 0.11.0 artifact" in pinned_block, "so does the Unreleased subsection"
assert "0.10.1" not in pinned_block, "no sentence in the block may name the previous version"
# Outside the two guarded regions a version names the release a capability landed in — permanently
# older, and rewriting it would falsify history rather than pin it.
assert "**v0.6.0**" in pinned_block, "Current Coverage's versions are history, not coordinates"

# The three guides were a by-hand step in release.md; gate 1 now makes the same edit README got.
GUIDE = (
    'implementation("io.github.famesjranko:musicmeta-core:0.10.1")\n'
    'implementation("com.github.famesjranko.musicmeta:musicmeta-okhttp:v0.10.1")\n'
)
pinned_guide = pin_guides(GUIDE, "0.11.0")
assert "musicmeta-core:0.11.0" in pinned_guide
assert "musicmeta-okhttp:v0.11.0" in pinned_guide, "the JitPack form keeps its v prefix"
assert "0.10.1" not in pinned_guide

# --- migration guide ------------------------------------------------------------------------------
from pin_release import pin_migration_guide

GUIDE_UNRELEASED = (
    "# Migration guide\n\n## Unreleased\n\n### A break\n\nbefore/after here.\n\n## 0.10.0\n\n### An older break\n"
)
pinned_guide_heading = pin_migration_guide(GUIDE_UNRELEASED, "0.11.0")
assert "## 0.11.0" in pinned_guide_heading
assert "## Unreleased" not in pinned_guide_heading
assert "## 0.10.0" in pinned_guide_heading, "older sections are untouched"

# Absent heading is not an error, and the guide is returned unchanged.
GUIDE_NO_UNRELEASED = "# Migration guide\n\n## 0.10.0\n\n### An older break\n"
assert pin_migration_guide(GUIDE_NO_UNRELEASED, "0.11.0") == GUIDE_NO_UNRELEASED

# --- against the real files ---------------------------------------------------------------------
# State-agnostic on purpose: this runs on every commit, including the release branch (target
# version freshly pinned, [Unreleased] empty) and main right after a release merges. A hard-coded
# target version made the release branch unmergeable by construction. So derive the target from
# the last pinned version, and accept the one refusal a freshly-cut release legitimately produces.
# The refusal *logic* (empty, double-pin, break-in-a-patch) is covered by the fixtures above.
root = Path(__file__).resolve().parent.parent.parent
live = (root / "CHANGELOG.md").read_text(encoding="utf-8")
last = released_versions(live)[0]
major, minor, _ = (int(p) for p in last.split("."))
next_minor = f"{major}.{minor + 1}.0"
try:
    real = pin_changelog(live, next_minor, "2026-07-23")
    assert extract_section(real, next_minor), "the live [Unreleased] section must pin and extract"
    roadmap = pin_roadmap((root / "ROADMAP.md").read_text(encoding="utf-8"), next_minor)
    assert f"## Where We Are (v{next_minor})" in roadmap
    migration = (root / "docs" / "guides" / "migration.md").read_text(encoding="utf-8")
    assert "## Unreleased" in migration, "the live migration guide should still head its newest group Unreleased"
    pinned_migration = pin_migration_guide(migration, next_minor)
    assert f"## {next_minor}" in pinned_migration
    assert "## Unreleased" not in pinned_migration
except PinError as e:
    assert "empty" in str(e), f"live CHANGELOG refused to pin for an unexpected reason: {e}"

print("pin_release: all checks passed")
