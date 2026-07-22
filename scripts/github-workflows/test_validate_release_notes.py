#!/usr/bin/env python3
"""Runnable self-check: `python3 test_validate_release_notes.py` (no framework needed)."""
from validate_release_notes import validate

GOOD = """Patch release — first since 0.10.0.

## Installation

**Maven Central** — [`io.github.famesjranko:musicmeta-core:0.10.1`](https://central.sonatype.com/artifact/io.github.famesjranko/musicmeta-core/0.10.1)

```kotlin
implementation("io.github.famesjranko:musicmeta-core:0.10.1")
implementation("io.github.famesjranko:musicmeta-okhttp:0.10.1")   // Optional
```

**JitPack** — [`v0.10.1`](https://jitpack.io/#famesjranko/musicmeta/v0.10.1)

```kotlin
implementation("com.github.famesjranko.musicmeta:musicmeta-core:v0.10.1")
```

**Full Changelog**: https://github.com/famesjranko/musicmeta/compare/v0.10.0...v0.10.1
"""

FLOATING = GOOD + "\n[![Maven Central](https://img.shields.io/maven-central/v/io.github.famesjranko/musicmeta-core)](https://central.sonatype.com/artifact/io.github.famesjranko/musicmeta-core)\n"
WRONG_MVN = GOOD.replace("musicmeta-okhttp:0.10.1", "musicmeta-okhttp:0.10.0")
WRONG_JIT = GOOD.replace("musicmeta-core:v0.10.1", "musicmeta-core:v0.9.9")  # only the JitPack coord has the 'v'
MISSING = "Just a one-line summary, no install block.\n"


def main() -> None:
    # Given valid, pinned notes -> no errors (and the compare link's older version must not trip it).
    assert validate(GOOD, "0.10.1") == [], validate(GOOD, "0.10.1")
    assert validate(GOOD, "v0.10.1") == []  # leading v tolerated

    # Given a floating badge -> flagged.
    assert any("floating" in e for e in validate(FLOATING, "0.10.1"))

    # Given a mismatched Maven coordinate -> flagged.
    assert any("not pinned" in e for e in validate(WRONG_MVN, "0.10.1")), validate(WRONG_MVN, "0.10.1")

    # Given a mismatched JitPack coordinate -> flagged.
    assert any("JitPack" in e for e in validate(WRONG_JIT, "0.10.1")), validate(WRONG_JIT, "0.10.1")

    # Given no install block -> the missing-core-coordinate rule fires.
    assert any("Missing the pinned" in e for e in validate(MISSING, "0.10.1"))

    # 0.10.10 must not satisfy a 0.10.1 pin.
    assert any("not pinned" in e for e in validate(GOOD.replace(":0.10.1", ":0.10.10"), "0.10.1"))

    print("all assertions passed")


if __name__ == "__main__":
    main()
