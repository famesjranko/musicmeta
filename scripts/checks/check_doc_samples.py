#!/usr/bin/env python3
"""Extract every ```kotlin fence in `docs/guides/*.md` into a compilable `docs-samples/` source file.

A doc sample is a claim about the API that nothing checks — the two guides fixed by hand before this
existed are exactly what a reader trusts and a build never verifies. This script turns each fence
into a `.kt` file `docs-samples/src/main/kotlin/` compiles as part of `./check`'s build layer (see
`check`'s "doc samples" step), so a sample that drifts from the real API fails a compile instead of
lying to the next reader. It does not run the samples — only proves they still type-check.

**Extraction, one file per fence:** a fence whose depth-0 lines are all declarations
(`import`/`fun`/`class`/`interface`/`object`/`typealias`/an annotation — deliberately not `val`/`var`,
see `classify`'s docstring) is written as-is; anything else — a bare statement (a method call), a
`val`/`var`, or a repeated top-level name (two alternative `val engine = ...` examples in one fence,
say) — is wrapped in `private suspend fun sample() { ... }` instead. Depth is tracked by a
plain running count of `(`/`)`/`{`/`}` per line after stripping `//` comments and `"..."` string
literals — a heuristic, not a Kotlin parser, matching this directory's existing checks. Every
generated file is given a unique package (so two fences never collide) and a wildcard import of every
`com.landofoz.musicmeta*` package musicmeta-core and musicmeta-okhttp publish, plus one first-line
comment naming its source: `// docs/guides/<file>.md snippet <n>`.

**Opt-out:** an HTML comment on the line immediately before a fence, `<!-- no-compile: <reason> -->`,
excludes that block; an empty reason is an extractor error, not a silent skip. A fence that is
pseudo-code, an elided fragment relying on a binding a *different* fence established earlier in the
same guide (most fences under a running "you already built `engine`" narrative are this shape), or
another library's API (Room, Hilt, WorkManager, `android.util.Log`) is a legitimate reason.

**`android.md` is out of scope.** Every fence in it is Room/Hilt/WorkManager/Android-framework code a
plain JVM module cannot compile without pulling in the Android SDK — opted out file-wide via the
marker above each fence, not a special case in this script.

    python3 check_doc_samples.py [--root PATH] [--out PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GUIDES_DIR = "docs/guides"
OUT_DIR = "docs-samples/build/generated-samples/src/main/kotlin"
FENCE_RE = re.compile(r"^\s*```kotlin\s*$")
FENCE_END_RE = re.compile(r"^\s*```\s*$")
NO_COMPILE_RE = re.compile(r"^\s*<!--\s*no-compile:\s*(.*?)\s*-->\s*$")

# What every generated file sees without asking — the library's own public surface. Read from the
# api/*.api files rather than hand-maintained here, so a thirteenth provider package appears without
# an edit to this list; see `public_packages`.
API_FILES = (
    "musicmeta-core/api/musicmeta-core.api",
    "musicmeta-okhttp/api/musicmeta-okhttp.api",
)
PACKAGE_LINE_RE = re.compile(r"^public\s+(?:final\s+)?(?:class|interface|object|abstract class|enum class)\s+(\S+)")

DECLARATION_KEYWORDS = {
    "import",
    "fun",
    "class",
    "interface",
    "object",
    "val",
    "var",
    "typealias",
}
# Modifiers that can precede a declaration keyword on the same line — stripped before the keyword
# check so `private suspend fun` still reads as a `fun` declaration.
MODIFIERS = {
    "public",
    "internal",
    "private",
    "protected",
    "open",
    "abstract",
    "final",
    "sealed",
    "data",
    "enum",
    "inline",
    "suspend",
    "override",
    "lateinit",
    "const",
    "inner",
    "companion",
    "annotation",
    "operator",
    "infix",
    "tailrec",
    "external",
    "expect",
    "actual",
    "vararg",
    "crossinline",
    "noinline",
    "value",
    "fun",  # `fun interface`
}
NAME_RE = re.compile(r"^(val|var|fun|class|interface|object|typealias)\s+(\w+)")


class ExtractionError(Exception):
    """A markdown file is malformed in a way the extractor refuses to guess past — e.g. an empty
    `no-compile` reason. Distinct from "found nothing to compile", which is not an error."""


def public_packages(root: Path) -> list[str]:
    """Every package the published `.api` files declare a public top-level type in, sorted."""
    packages: set[str] = set()
    for rel in API_FILES:
        path = root / rel
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            match = PACKAGE_LINE_RE.match(line)
            if not match:
                continue
            packages.add(match.group(1).rsplit("/", 1)[0].replace("/", "."))
    return sorted(packages)


def strip_noise(line: str) -> str:
    """`line` with string literals and a trailing `//` comment blanked out, for depth counting only.

    Not a Kotlin lexer — quotes inside a `//` comment, raw strings, and escaped quotes inside a
    string are not handled, matching the heuristic-over-parser idiom the sibling checks use. Good
    enough for doc samples, which do not write adversarial string content.
    """
    out = []
    in_string = False
    i = 0
    while i < len(line):
        ch = line[i]
        if not in_string and ch == "/" and i + 1 < len(line) and line[i + 1] == "/":
            break
        if ch == '"':
            in_string = not in_string
            out.append(" ")
        elif in_string:
            out.append(" ")
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def leading_keyword(line: str) -> str | None:
    """The declaration keyword a depth-0 line starts with, after stripping modifiers — or None."""
    tokens = line.split()
    for token in tokens:
        if token in MODIFIERS:
            continue
        if token in DECLARATION_KEYWORDS:
            return token
        if token.startswith("@"):
            return "@"
        return None
    return None


def classify(body_lines: list[str]) -> str:
    """ "top-level" if every depth-0 statement in `body_lines` is a declaration, no top-level name
    repeats, and none of them is `val`/`var`; "wrap" otherwise. See the module docstring for why the
    declaration and name checks matter.

    `val`/`var` is excluded from the top-level path even though the design allows it there: a
    top-level property's initializer runs outside any coroutine, so a sample whose one line is
    `val profile = engine.artistProfile(...)` — a suspend call — fails to compile for a reason that
    has nothing to do with whether the sample matches the API. Wrapping loses nothing a `val` sample
    needs (a local `val` typechecks against the same API a file-scope one would) and gains a
    coroutine context every suspend call in these guides needs anyway.

    That exclusion backs off when the fence also declares a named `class`/`interface`/`object`: a
    *local* named type is illegal Kotlin (`object MyMerger cannot be local`), so wrapping would break
    a fence that registers the type it just declared (`val engine = ...addMerger(MyCustomMerger)...`)
    — worse than the suspend-at-top-level failure this whole exclusion exists to avoid. No fence in
    these guides combines a named type with a top-level suspend call, so this is safe today; a future
    one would need the same treatment as any other structural mismatch — an opt-out.
    """
    depth = 0
    names: list[str] = []
    has_val_or_var = False
    has_named_type = False
    for raw in body_lines:
        stripped = raw.strip()
        # A depth-0 line opening with `.`/`?.` is a fluent-chain continuation of the statement the
        # previous line started (`val engine = Builder()\n    .withDefaultProviders()`), not a fresh
        # depth-0 statement — the true statement boundary is wherever the chain that opened it began.
        is_continuation = stripped.startswith(".") or stripped.startswith("?.")
        if depth == 0 and stripped and not stripped.startswith("//") and not is_continuation:
            keyword = leading_keyword(stripped)
            if keyword is None:
                return "wrap"
            if keyword in ("val", "var"):
                has_val_or_var = True
            if keyword in ("class", "interface", "object"):
                has_named_type = True
            if keyword != "@":
                match = NAME_RE.match(stripped)
                if match:
                    names.append(match.group(2))
        noise_free = strip_noise(raw)
        depth += noise_free.count("(") + noise_free.count("{")
        depth -= noise_free.count(")") + noise_free.count("}")
    if len(names) != len(set(names)):
        return "wrap"
    if has_val_or_var and not has_named_type:
        return "wrap"
    return "top-level"


def package_name(guide_stem: str, index: int) -> str:
    slug = guide_stem.replace("-", "_")
    return f"doc.samples.{slug}.snippet{index}"


def render(guide_rel: str, index: int, guide_stem: str, body_lines: list[str], packages: list[str]) -> str:
    header = f"// {guide_rel} snippet {index}\n"
    package_decl = f"package {package_name(guide_stem, index)}\n\n"
    wildcard_imports = "".join(f"import {pkg}.*\n" for pkg in packages)
    # Kotlin requires every import contiguous after the package line — a fence that writes its own
    # `import` (naming the type it implements, say) cannot leave it where the fence put it once the
    # rest is wrapped in a function, so every explicit import is hoisted up here regardless of where
    # in the fence it appeared.
    own_imports = [line for line in body_lines if line.strip().startswith("import ")]
    rest = [line for line in body_lines if not line.strip().startswith("import ")]
    imports = wildcard_imports + "".join(f"{line.strip()}\n" for line in own_imports) + "\n"
    body = "\n".join(rest)
    if classify(body_lines) == "top-level":
        return header + package_decl + imports + body + "\n"
    indented = "\n".join(("    " + line if line.strip() else line) for line in rest)
    return header + package_decl + imports + "private suspend fun sample() {\n" + indented + "\n}\n"


def extract_guide(path: Path, packages: list[str]) -> tuple[list[tuple[str, str]], list[str]]:
    """Returns (list of (filename, content) for compiled snippets, list of skip descriptions)."""
    lines = path.read_text(encoding="utf-8").splitlines()
    compiled: list[tuple[str, str]] = []
    skipped: list[str] = []
    guide_rel = f"{GUIDES_DIR}/{path.name}"
    index = 0
    i = 0
    while i < len(lines):
        if not FENCE_RE.match(lines[i]):
            i += 1
            continue
        index += 1
        prev = lines[i - 1] if i > 0 else ""
        no_compile = NO_COMPILE_RE.match(prev)
        body_start = i + 1
        j = body_start
        while j < len(lines) and not FENCE_END_RE.match(lines[j]):
            j += 1
        if j >= len(lines):
            raise ExtractionError(f"{guide_rel}: snippet {index} opens a ```kotlin fence with no closing ``` ")
        body_lines = lines[body_start:j]
        if no_compile:
            reason = no_compile.group(1).strip()
            if not reason:
                raise ExtractionError(
                    f"{guide_rel}: snippet {index} has an empty no-compile reason — "
                    "state why, `<!-- no-compile: <reason> -->`"
                )
            skipped.append(f"{guide_rel} snippet {index}: {reason}")
        else:
            filename = f"{path.stem.replace('-', '_')}_snippet{index}.kt"
            compiled.append((filename, render(guide_rel, index, path.stem, body_lines, packages)))
        i = j + 1
    return compiled, skipped


def run(root: Path, out_dir: Path) -> tuple[int, int]:
    """Writes every compiled sample under `out_dir`, clearing it first. Returns (compiled, skipped)."""
    guides_dir = root / GUIDES_DIR
    guides = sorted(guides_dir.glob("*.md")) if guides_dir.is_dir() else []
    packages = public_packages(root)

    if out_dir.is_dir():
        for stale in out_dir.glob("*.kt"):
            stale.unlink()
    out_dir.mkdir(parents=True, exist_ok=True)

    total_compiled = 0
    total_skipped = 0
    for guide in guides:
        compiled, skipped = extract_guide(guide, packages)
        for filename, content in compiled:
            (out_dir / filename).write_text(content, encoding="utf-8")
        total_compiled += len(compiled)
        total_skipped += len(skipped)
    return total_compiled, total_skipped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract docs/guides/*.md Kotlin fences into docs-samples/.")
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    parser.add_argument("--out", help="output directory (default: docs-samples/build/generated-samples/...)")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    out_dir = Path(args.out).resolve() if args.out else root / OUT_DIR

    try:
        compiled, skipped = run(root, out_dir)
    except ExtractionError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 2

    print(f"{compiled} doc sample(s) extracted to {out_dir}, {skipped} opted out.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
