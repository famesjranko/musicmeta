#!/usr/bin/env python3
"""Differential-test `_code_mask()` against the Kotlin compiler's own lexer.

`check_conventions.py` decides whether every other piece of Kotlin in this repo is acceptable, and
its `_code_mask()` — which character is code, which is comment or string text — is what all three
rules read. When that mask is wrong the gate does not throw; it silently stops reporting
violations and `./check` still prints green. Four review rounds found seven such bugs, every one a
silent false negative. This replaces "someone reviewed it carefully" with a mechanism.

`test_check_conventions.py` covers the cases we thought of. This covers the ones we did not: it
lexes every Kotlin file in the repo with `KotlinLexer` and compares character by character.

Three parts, and the third is the one that matters:

  corpus    every main and test source — real code, real shapes
  probes    synthetic constructs the corpus does not contain
  mutations each historical bug reintroduced, asserting the harness catches it

A harness never proven to catch anything is worth nothing. The mutation suite is this harness's
own test — and mutation 8 (block comments not nesting) is caught by *no file in the repo*, which
is why the probe suite is mandatory rather than a nicety.

    python3 test_code_mask.py [--verbose]
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from check_conventions import _code_mask  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent.parent
KT_MASK_JAVA = Path(__file__).resolve().parent / "KtMask.java"
CONVENTIONS_PY = Path(__file__).resolve().parent / "check_conventions.py"
CLASSPATH_CACHE = ROOT / "build" / "ktlexer-classpath.txt"


# --- the oracle -------------------------------------------------------------------------------


def classpath() -> str:
    """Kotlin's lexer jars, via Gradle.

    Never a hardcoded `~/.gradle/caches/...` path: that directory is content-addressed, so it moves
    on any version bump, and CI sets a different GRADLE_USER_HOME. Cached because a Gradle
    invocation costs seconds and this runs in the edit loop; regenerated when the version catalog
    is newer than the cache, so a Kotlin bump cannot leave the oracle on the old lexer.
    """
    catalog = ROOT / "gradle" / "libs.versions.toml"
    if CLASSPATH_CACHE.exists() and CLASSPATH_CACHE.stat().st_mtime >= catalog.stat().st_mtime:
        cached = CLASSPATH_CACHE.read_text().strip()
        # Existence check, not just non-empty: `gradle --refresh-dependencies`, a cache clean or a
        # new GRADLE_USER_HOME leaves the cached paths pointing at nothing, and the resulting
        # NoClassDefFoundError says nothing about why.
        if cached and all(Path(entry).exists() for entry in cached.split(os.pathsep)):
            return cached
    result = subprocess.run(
        ["./gradlew", "-q", "ktLexerClasspath"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    )
    resolved = result.stdout.strip().splitlines()[-1]
    CLASSPATH_CACHE.parent.mkdir(parents=True, exist_ok=True)
    CLASSPATH_CACHE.write_text(resolved + "\n")
    return resolved


def oracle_masks(paths: list[Path]) -> dict[Path, list[bool]]:
    """Lex every file in one JVM and return per-character code/text, True where code.

    One JVM for all files, not one per file: startup and the class-init of KtTokens dominate, so
    forking per file turns 0.2s into ~30s.
    """
    if not paths:
        return {}
    result = subprocess.run(
        ["java", "-cp", classpath(), str(KT_MASK_JAVA), *[str(p) for p in paths]],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"KtMask failed:\n{result.stderr}")

    masks: dict[Path, list[bool]] = {}
    current: list[bool] | None = None
    for line in result.stdout.splitlines():
        if line.startswith("# "):
            current = []
            masks[Path(line[2:])] = current
        elif line:
            start, end, kind = line.split(" ")
            assert current is not None, "token line before any file header"
            assert len(current) == int(start), "lexer spans are not contiguous"
            current.extend([kind == "CODE"] * (int(end) - int(start)))
    return masks


def read_source(path: Path) -> str:
    """Decode as UTF-8 from bytes, never through text mode.

    Text mode collapses CRLF to a single LF, which shifts every offset after the first line ending
    and makes the disagreement look like a scanner bug. No CRLF file exists today, so this would
    only appear after a .gitattributes change or a Windows contributor — i.e. mysteriously.
    """
    return path.read_bytes().decode("utf-8")


# --- comparison -------------------------------------------------------------------------------


def disagreements(source: str, mine: list[bool], theirs: list[bool], label: str) -> list[str]:
    """Report every position where the scanner and the lexer classify a character differently."""
    if len(mine) != len(theirs):
        return [f"{label}: length mismatch — scanner {len(mine)}, lexer {len(theirs)}"]

    findings: list[str] = []
    i = 0
    while i < len(mine) and len(findings) < 5:
        if mine[i] == theirs[i]:
            i += 1
            continue
        start = i
        while i < len(mine) and mine[i] != theirs[i]:
            i += 1
        line = source.count("\n", 0, start) + 1
        before = source[max(0, start - 60) : start].replace("\n", "\\n")
        context = before + source[start : min(len(source), i + 60)].replace("\n", "\\n")
        findings.append(
            f"{label}:{line} (chars {start}..{i}): scanner says "
            f"{'code' if mine[start] else 'text'}, lexer says {'code' if theirs[start] else 'text'}\n"
            f"    {context}\n"
            f"    {' ' * len(before)}^"
        )
    return findings


# --- corpus -----------------------------------------------------------------------------------


def corpus(root: Path) -> list[Path]:
    """Every Kotlin source the repo owns, including tests and `demo/`.

    Wider than what the gate scans on purpose. `check_conventions.py` only reads main sources, but
    the question here is whether the mask is correct, not whether a file is in scope — and the test
    tree and the demo hold string shapes the main sources do not. More legal Kotlin is strictly
    better for an oracle comparison.
    """
    return sorted(
        path
        for pattern in ("*/src/main/**/*.kt", "*/src/test/**/*.kt")
        for path in root.glob(pattern)
        if "/build/" not in path.as_posix()
    )


# --- probes -----------------------------------------------------------------------------------

# Constructs the corpus does not contain. Each is Kotlin the lexer accepts; the point is not that
# they are realistic but that they are legal, since the gate must not be fooled by legal code it
# has never seen. The nested block comment is the load-bearing one: mutation 8 is caught by zero
# of the repo's files.
PROBES: dict[str, str] = {
    "nested_block_comment": "/* outer /* inner */ still commented !! */\nval a = 1\n",
    "nested_block_comment_deep": "/* a /* b /* c */ d */ e !! */\nval a = 1\n",
    "raw_string_ending_in_quote": 'val s = """he said "hi""""\nval b = 2\n',
    "raw_string_with_four_quotes": 'val s = """x""""\nval b = 2\n',
    "nested_string_in_template": 'val s = "${enc(id, "UTF-8")}"\nval b = 2\n',
    "lambda_in_template": 'val s = "${list.map { it }}"\nval b = 2\n',
    "template_holding_a_raw_string": 'val s = "${f("""x""")}"\nval b = 2\n',
    "backtick_identifier_with_quote": 'fun `it\'s a "test"`() = Unit\nval b = 2\n',
    "backtick_identifier_with_dollar_brace": "fun `a \\${b} c`() = Unit\nval b = 2\n",
    "escaped_quote_in_string": 'val s = "a \\" b"\nval c = 3\n',
    "escaped_backslash_before_quote": 'val s = "a\\\\"\nval c = 3\n',
    "escaped_dollar": 'val s = "\\$notATemplate"\nval c = 3\n',
    "char_literal_quote": "val c = '\"'\nval d = 4\n",
    "char_literal_escaped_backslash": "val c = '\\\\'\nval d = 4\n",
    "char_literal_apostrophe": "val c = '\\''\nval d = 4\n",
    "line_comment_holding_a_quote": 'val a = 1 // "unclosed\nval b = 2\n',
    "url_in_string": 'val u = "https://host/path"\nval b = 2\n',
    "kdoc_with_code_fence": "/**\n * ```\n * val x = y!!\n * ```\n */\nval a = 1\n",
    "short_template": 'val s = "$name and $other"\nval b = 2\n',
    "template_inside_raw_string": 'val s = """${a + b}"""\nval c = 3\n',
    "multiline_raw_string": 'val s = """\n  // not a comment\n  "quoted"\n"""\nval b = 2\n',
    "block_comment_holding_a_string": '/* val s = "unclosed */\nval a = 1\n',
    "string_holding_block_comment_open": 'val s = "/*"\nval a = 1\n',
    "annotation_and_operators": '@Suppress("x")\nval r = 1..2 step 3\nval q = a?.b ?: c\n',
}


def write_probes(directory: Path) -> dict[Path, str]:
    written = {}
    for name, source in PROBES.items():
        path = directory / f"{name}.kt"
        path.write_bytes(source.encode("utf-8"))
        written[path] = source
    return written


# --- mutations --------------------------------------------------------------------------------

# Each entry reintroduces one of the seven bugs review found in `_code_mask()`, plus the nested
# block comment case that only a probe catches. They are applied as textual edits to
# check_conventions.py rather than kept as a forked copy, so they cannot rot into testing an old
# implementation — if the anchor text stops matching, the run fails and says which mutation to
# re-derive. That failure is the intended behaviour, not an inconvenience.
MUTATIONS: list[tuple[str, str, str]] = [
    (
        "raw string closes on the first three quotes, not the last",
        "while i + run < n and source[i + run] == '\"':",
        "while i + run < n and source[i + run] == '\"' and run < 3:",
    ),
    (
        "backtick-escaped identifiers not skipped",
        'if source[i] == "`":',
        "if False:  # mutation",
    ),
    (
        "no escape handling inside normal strings",
        'if ctx == "string" and source[i] == "\\\\":',
        "if False:  # mutation",
    ),
    (
        "template braces not tracked",
        'if source[i] == "{" and ctx in ("template", "brace"):',
        "if False:  # mutation",
    ),
    (
        "char literals not skipped",
        'if source[i] == "\'":',
        "if False:  # mutation",
    ),
    (
        "line comments run to end of file, not end of line",
        'stop = source.find("\\n", i)',
        "stop = -1  # mutation",
    ),
    (
        "template contents treated as text rather than code",
        'stack.append("template")',
        "pass  # mutation",
    ),
    (
        "block comments do not nest",
        "depth += 1",
        "pass  # mutation",
    ),
]


def mutated_code_mask(anchor: str, replacement: str, description: str):  # noqa: ANN201
    """Load `_code_mask` from a copy of check_conventions.py with one defect reintroduced."""
    source = CONVENTIONS_PY.read_text()
    if source.count(anchor) != 1:
        raise SystemExit(
            f"mutation '{description}' anchors on text appearing {source.count(anchor)} times in "
            f"check_conventions.py (expected exactly 1). The scanner was edited — re-derive this "
            f"mutation so it still reintroduces the bug it names:\n    {anchor}"
        )
    module = types.ModuleType("mutated_check_conventions")
    exec(compile(source.replace(anchor, replacement), "<mutated>", "exec"), module.__dict__)  # noqa: S102
    return module._code_mask


# --- run --------------------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Differential-test _code_mask() against KotlinLexer.")
    parser.add_argument("--verbose", action="store_true", help="print per-mutation detail")
    args = parser.parse_args(argv)

    with tempfile.TemporaryDirectory() as tmp:
        probes = write_probes(Path(tmp))
        files = corpus(ROOT) + sorted(probes)
        masks = oracle_masks(files)

        sources = {path: read_source(path) for path in files}
        findings: list[str] = []
        for path in files:
            label = path.name if path in probes else path.relative_to(ROOT).as_posix()
            findings.extend(disagreements(sources[path], _code_mask(sources[path]), masks[path], label))

        if findings:
            for finding in findings:
                print(finding, file=sys.stderr)
            print(f"\n{len(findings)} disagreement(s) with KotlinLexer.", file=sys.stderr)
            return 2

        total = sum(len(sources[p]) for p in files)
        print(f"code mask agrees with KotlinLexer across {len(files)} files ({total:,} chars).")

        # The harness's own test. A mutation is "caught" as soon as one file disagrees, so this
        # stops at the first — reproducing the full per-mutation file counts costs a whole corpus
        # pass each and proves nothing more.
        uncaught = []
        for description, anchor, replacement in MUTATIONS:
            mask_fn = mutated_code_mask(anchor, replacement, description)
            caught_by = next(
                (path for path in files if mask_fn(sources[path]) != masks[path]),
                None,
            )
            if caught_by is None:
                uncaught.append(description)
            elif args.verbose:
                print(f"  caught by {caught_by.name}: {description}")

        if uncaught:
            for description in uncaught:
                print(f"MUTATION NOT CAUGHT: {description}", file=sys.stderr)
            print(
                "\nThe harness failed to notice a known defect. Either the corpus no longer "
                "contains the construct, or the mutation no longer reintroduces the bug — add a "
                "probe to PROBES rather than deleting the mutation.",
                file=sys.stderr,
            )
            return 2

        print(f"all {len(MUTATIONS)} mutations caught.")
    return 0


if __name__ == "__main__":
    os.chdir(ROOT)
    raise SystemExit(main())
