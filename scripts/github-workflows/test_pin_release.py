#!/usr/bin/env python3
"""Runnable self-check: `python3 test_pin_release.py` (no framework needed)."""

from pathlib import Path

from build_release_notes import extract_section
from pin_release import PinError, pin_changelog, pin_roadmap

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

# --- roadmap ------------------------------------------------------------------------------------
assert pin_roadmap("## Where We Are (v0.10.1)\n\ntext\n", "0.11.0") == "## Where We Are (v0.11.0)\n\ntext\n"
# Only the first, and a missing heading is not an error.
assert pin_roadmap("no heading here\n", "0.11.0") == "no heading here\n"

# --- against the real files ---------------------------------------------------------------------
root = Path(__file__).resolve().parent.parent.parent
real = pin_changelog((root / "CHANGELOG.md").read_text(encoding="utf-8"), "0.11.0", "2026-07-23")
assert extract_section(real, "0.11.0"), "the live [Unreleased] section must pin and extract"
assert "## Where We Are (v0.11.0)" in pin_roadmap((root / "ROADMAP.md").read_text(encoding="utf-8"), "0.11.0")

print("pin_release: all checks passed")
