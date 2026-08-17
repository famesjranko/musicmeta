#!/usr/bin/env python3
"""Compile every ```kotlin fence in `docs/guides/*.md`, in reading order, as one narrative per guide.

A doc sample is a claim about the API that nothing checks — the two guides fixed by hand before this
existed are exactly what a reader trusts and a build never verifies. This script turns each guide's
fences into `.kt` files `docs-samples/` compiles as part of `./check`'s build layer (see `check`'s
"doc samples" step), so a sample that drifts from the real API fails a compile instead of lying to
the next reader. It does not run the samples — only proves they still type-check.

**Per-guide accumulating narrative.** A guide is not a bag of independent fences: it reads as one
narrative, and a fence forty lines down routinely assumes a `val` an earlier fence in the same guide
declared. Compiling each fence alone makes that assumption a compile error unrelated to API drift,
and the wrong fix is opting affected fences out of checking — that means nothing checked the guide as
its reader experiences it. Per guide instead of per fence:

- A fence whose depth-0 lines are all declarations (`classify`'s "top-level" path) is written as its
  own top-level declaration in the guide's shared package, so a later fence sees it without an import.
- Every other fence — a bare statement, a `val`/`var` — is appended, in fence order, to one
  `private suspend fun narrative()` for the guide, each preceded by its own
  `// docs/guides/<file>.md snippet <n>` marker line so a compile error's line still identifies which
  fence broke it: read the nearest marker above the reported line.
- A fence whose own `val`/`var` name was already bound earlier in the same narrative — two
  alternative engine configs shown back to back, say — is wrapped alone in `run { ... }`, isolating
  the rebind in a nested scope Kotlin's shadowing rules allow, without letting it leak forward. A
  fence that rebinds the *same* name twice *within itself* cannot be isolated this way — a single
  `run { }` still puts both declarations in one scope — so that case is an `ExtractionError` demanding
  an explicit `<!-- no-compile: ... -->` instead of a silent skip.
- Every guide's narrative gets a mechanism-owned prelude prepended, uniformly, quick-start.md's own
  included: `docs-samples/src/main/kotlin/doc/samples/prelude/Prelude.kt`, a real, compiled,
  checked-in file, not a string in this script. It is "the context every guide's narrative may
  assume": a default `engine`, a default `results`, and the small placeholders (`myOkHttpClient`,
  `opaqueEntityKey`, `candidate`, `topTrack`, `topTracks`, `myLibrary`) enough guides use as a
  stand-in for "your own instance" to be worth defining once. quick-start.md's own engine-teaching
  fence collides with the prelude's `engine` and so is isolated in `run { }` like any other rebind —
  still compiled, still verified, just no longer also the guide's `engine` for later fences.

**Opt-out, unchanged:** an HTML comment on the line immediately before a fence, `<!-- no-compile:
<reason> -->`, excludes that block; an empty reason is an extractor error, not a silent skip. What
remains a legitimate reason after accumulation and the prelude: pseudo-code, an elided fragment
(`/* ... */`, `// ... more here`), a placeholder the prelude does not define because a real fake would
be nontrivial (`RedisClient`, a full custom `HttpClient`), a fence that depends on an *earlier fence
that is itself opted out*, a Gradle build-script fragment (not code either toolchain compiles), or
another library's API neither target wires in (`android.util.Log`, JUnit).

**`android.md` compiles against the real Android toolchain, not the JVM one.** Every other guide's
fences land in `docs-samples/`, a plain JVM module; `android.md`'s land in `docs-samples-android/`,
a `com.android.library` composite build that actually depends on `musicmeta-android` and Room/Hilt/
WorkManager, the same extraction and narrative rules applied to a second output directory and a
second, Android-flavoured prelude (`docs-samples-android/.../prelude/AndroidPrelude.kt`). One
extractor, two compiled targets — `run()` writes every other guide to `--out` and `android.md` to
`--android-out` on the same pass.

    python3 check_doc_samples.py [--root PATH] [--out PATH] [--android-out PATH]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GUIDES_DIR = "docs/guides"
OUT_DIR = "docs-samples/build/generated-samples/src/main/kotlin"
ANDROID_OUT_DIR = "docs-samples-android/build/generated-samples/src/main/kotlin"
# The one guide whose fences use Room/Hilt/WorkManager/Android-framework types a plain JVM module
# cannot see — routed to `ANDROID_OUT_DIR`, compiled by docs-samples-android/ instead of docs-samples/.
ANDROID_GUIDE_NAME = "android.md"
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
# android.md's own API surface: musicmeta-core plus musicmeta-android's own published types
# (RoomEnrichmentCache, EnrichmentCacheDatabase, HiltEnrichmentModule, EnrichmentWorker) — not
# `API_FILES`, since android.md never uses musicmeta-okhttp and docs-samples-android does not
# depend on it.
ANDROID_API_FILES = (
    "musicmeta-core/api/musicmeta-core.api",
    "musicmeta-android/api/musicmeta-android.api",
)
PACKAGE_LINE_RE = re.compile(r"^public\s+(?:final\s+)?(?:class|interface|object|abstract class|enum class)\s+(\S+)")

# Not part of musicmeta's own public surface, but needed to compile the one real, public-API fence
# that uses it (quick-start.md's `Flow.collect { }` over `enrichBatch`) — a stdlib-adjacent extension
# every real consumer of that call needs too, not a placeholder hiding anything.
EXTRA_WILDCARD_IMPORTS = ("kotlinx.coroutines.flow",)

# android.md's fences never write their own `import` lines (a doc convention this script does not
# police), so they need every third-party package they use wildcard-imported rather than declared
# per fence — the Room/Hilt/WorkManager/AndroidX surface musicmeta-android itself is built on.
ANDROID_EXTRA_WILDCARD_IMPORTS = (
    "android.content",
    "androidx.lifecycle",
    "androidx.room",
    "androidx.work",
    "dagger",
    "dagger.hilt",
    "dagger.hilt.components",
    "dagger.hilt.android.lifecycle",
    "javax.inject",
    "kotlinx.coroutines",
    "kotlinx.coroutines.flow",
)

# "the context every guide's narrative may assume" — see Prelude.kt's own KDoc for what each name is
# and why it is safe to fake. Order matters: `preludeResults` takes the just-bound `engine`.
PRELUDE_PACKAGE = "doc.samples.prelude"
PRELUDE_BINDINGS = (
    ("engine", f"{PRELUDE_PACKAGE}.preludeEngine()"),
    ("results", f"{PRELUDE_PACKAGE}.preludeResults(engine)"),
    ("opaqueEntityKey", f"{PRELUDE_PACKAGE}.preludeOpaqueEntityKey"),
    ("myOkHttpClient", f"{PRELUDE_PACKAGE}.preludeOkHttpClient"),
    ("candidate", f"{PRELUDE_PACKAGE}.preludeSearchCandidate"),
    ("topTrack", f"{PRELUDE_PACKAGE}.preludeTopTrack"),
    ("topTracks", f"{PRELUDE_PACKAGE}.preludeTopTracks"),
    ("myLibrary", f"{PRELUDE_PACKAGE}.preludeMyLibrary"),
    # A qualified callable reference (`pkg::fun`) does not parse with a multi-segment package on the
    # left, so these two bind a lambda that forwards to the qualified call instead.
    (
        "updateUI",
        f"{{ t: String, a: EnrichmentData.Artwork?, g: List<String> -> {PRELUDE_PACKAGE}.preludeUpdateUI(t, a, g) }}",
    ),
    ("showBanner", f"{{ m: String -> {PRELUDE_PACKAGE}.preludeShowBanner(m) }}"),
)
# Every guide gets the same prelude, quick-start.md included: its own first fence teaches `engine`
# from nothing and collides with the prelude's, but colliding is exactly the "rebinds an earlier
# name" case `render_narrative` already isolates in `run { }` — the fence still compiles and is still
# verified, it just does not additionally become the guide's `engine` for later fences the way it did
# before the prelude existed. Uniform beats a guide-shaped exception with only one member.

# android.md's own context, not the JVM prelude's: it never calls `enrich()` against a generic
# `results`, so binding one here would only be a name nothing reads. `engine`/`cache`/`db` are left
# unbound — the guide's own manual-setup fence declares all three and every later fence in the
# narrative sees them, the same forward visibility any other guide's own first fence gets.
ANDROID_PRELUDE_PACKAGE = "doc.samples.android.prelude"
ANDROID_PRELUDE_BINDINGS = (
    ("context", f"{ANDROID_PRELUDE_PACKAGE}.preludeContext"),
    ("albumIds", f"{ANDROID_PRELUDE_PACKAGE}.preludeAlbumIds"),
)

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


class ExtractionError(Exception):
    """A markdown file is malformed in a way the extractor refuses to guess past — e.g. an empty
    `no-compile` reason, or a fence that redeclares one of its own names twice. Distinct from "found
    nothing to compile", which is not an error."""


def _public_api_type_paths(root: Path, api_files: tuple[str, ...] = API_FILES) -> list[str]:
    """Every `some/package/SomeType` path `api_files` declares public, unsorted."""
    paths: list[str] = []
    for rel in api_files:
        path = root / rel
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            match = PACKAGE_LINE_RE.match(line)
            if match:
                paths.append(match.group(1))
    return paths


def public_packages(root: Path, api_files: tuple[str, ...] = API_FILES) -> list[str]:
    """Every package `api_files` declares a public top-level type in, sorted."""
    return sorted({path.rsplit("/", 1)[0].replace("/", ".") for path in _public_api_type_paths(root, api_files)})


def public_simple_names(root: Path, api_files: tuple[str, ...] = API_FILES) -> set[str]:
    """Every public top-level type's bare name (`EnrichmentResult`, not its package) `api_files`
    declares — a doc fence restating one of these under a wildcard-imported guide package would
    shadow the real type for every other fence in the guide (Kotlin resolves a same-package
    declaration over a star import); see `build_guide`'s use of this for why that fence gets isolated.
    """
    return {path.rsplit("/", 1)[-1].split("$", 1)[0] for path in _public_api_type_paths(root, api_files)}


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


NAME_TOKEN_RE = re.compile(r"^\w+")


def leading_keyword(line: str) -> str | None:
    """The declaration keyword a depth-0 line starts with, after stripping modifiers — or None."""
    return leading_declaration(line)[0]


def leading_declaration(line: str) -> tuple[str | None, str | None]:
    """(declaration keyword, declared name) for a depth-0 line, after stripping modifiers.

    The name comes from the token *after* the keyword, not a regex anchored at the line's start —
    `NAME_RE.match(line)` on `"sealed class Foo {"` fails because the match starts at `sealed`, not
    `class`, silently dropping every modified declaration from name tracking. Either half may be
    `None`: a bare statement has no keyword; a keyword with nothing after it (a stray `import` line,
    say) has no name.
    """
    tokens = line.split()
    for i, token in enumerate(tokens):
        if token in MODIFIERS:
            continue
        if token in DECLARATION_KEYWORDS:
            name = None
            if i + 1 < len(tokens):
                match = NAME_TOKEN_RE.match(tokens[i + 1])
                name = match.group(0) if match else None
            return token, name
        if token.startswith("@"):
            return "@", None
        return None, None
    return None, None


def _depth0_statements(body_lines: list[str]):
    """Yields each depth-0, non-comment, non-continuation line's stripped text.

    Shared walk behind both `classify` (does this fence stand alone at file scope?) and
    `depth0_declared_names` (what names does this fence bind, regardless of that?) — one heuristic
    depth tracker, not two copies drifting apart. A depth-0 line opening with `.`/`?.` is a
    fluent-chain continuation of the statement the previous line started (`val engine = Builder()\\n
    .withDefaultProviders()`), not a fresh statement — the true boundary is wherever the chain that
    opened it began.
    """
    depth = 0
    for raw in body_lines:
        stripped = raw.strip()
        is_continuation = stripped.startswith(".") or stripped.startswith("?.")
        if depth == 0 and stripped and not stripped.startswith("//") and not is_continuation:
            yield stripped
        noise_free = strip_noise(raw)
        depth += noise_free.count("(") + noise_free.count("{")
        depth -= noise_free.count(")") + noise_free.count("}")


def _depth0_declaration_names(body_lines: list[str], kinds: set[str] | None = None) -> list[str]:
    """Names bound by a depth-0 declaration whose keyword is in `kinds` (default: any of them)."""
    names = []
    for stripped in _depth0_statements(body_lines):
        keyword, name = leading_declaration(stripped)
        if keyword is None or keyword == "@":
            continue
        if kinds is not None and keyword not in kinds:
            continue
        if name:
            names.append(name)
    return names


def depth0_declared_names(body_lines: list[str]) -> list[str]:
    """Every name a depth-0 `val`/`var` in `body_lines` binds, in order, duplicates kept.

    Duplicates are the caller's problem: a name appearing twice means either an earlier fence needs
    isolating this one from (cross-fence — fixable), or this fence redeclares itself (internal —
    not fixable by wrapping the whole fence once; see `ExtractionError`'s docstring).
    """
    return _depth0_declaration_names(body_lines, kinds={"val", "var"})


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
    — worse than the suspend-at-top-level failure this whole exclusion exists to avoid.
    """
    names: list[str] = []
    has_val_or_var = False
    has_named_type = False
    for stripped in _depth0_statements(body_lines):
        keyword, name = leading_declaration(stripped)
        if keyword is None:
            return "wrap"
        if keyword in ("val", "var"):
            has_val_or_var = True
        if keyword in ("class", "interface", "object"):
            has_named_type = True
        if keyword != "@" and name:
            names.append(name)
    if len(names) != len(set(names)):
        return "wrap"
    if has_val_or_var and not has_named_type:
        return "wrap"
    return "top-level"


def guide_package(guide_stem: str) -> str:
    return f"doc.samples.{guide_stem.replace('-', '_')}"


def _split_own_imports(body_lines: list[str]) -> tuple[list[str], list[str]]:
    """(this fence's own `import` lines, stripped; everything else, unchanged)."""
    own = [line.strip() for line in body_lines if line.strip().startswith("import ")]
    rest = [line for line in body_lines if not line.strip().startswith("import ")]
    return own, rest


def _file_header(
    guide_rel: str,
    snippet_label: str,
    package: str,
    packages: list[str],
    extra_imports: list[str],
    extra_wildcard_imports: tuple[str, ...] = EXTRA_WILDCARD_IMPORTS,
) -> str:
    header = f"// {guide_rel} {snippet_label}\n"
    package_decl = f"package {package}\n\n"
    wildcard_imports = "".join(f"import {pkg}.*\n" for pkg in packages)
    wildcard_imports += "".join(f"import {pkg}.*\n" for pkg in extra_wildcard_imports)
    own = "".join(f"{line}\n" for line in dict.fromkeys(extra_imports))  # de-duplicated, order kept
    return header + package_decl + wildcard_imports + own + "\n"


def render_top_level_fence(
    guide_rel: str,
    index: int,
    package: str,
    body_lines: list[str],
    packages: list[str],
    extra_wildcard_imports: tuple[str, ...] = EXTRA_WILDCARD_IMPORTS,
    prelude_packages: tuple[str, ...] = (),
) -> str:
    """`prelude_packages` is wildcard-imported alongside `packages` — a top-level fence sees no
    `narrative()` locals, so a fence that names a prelude-defined domain type directly (android.md's
    `AlbumEnrichmentWorker` taking an `AlbumRepository` the guide never defines) needs the type
    itself in scope, not a `val` binding narrative-only fences get.
    """
    own_imports, rest = _split_own_imports(body_lines)
    header = _file_header(
        guide_rel,
        f"snippet {index}",
        package,
        list(packages) + list(prelude_packages),
        own_imports,
        extra_wildcard_imports,
    )
    return header + "\n".join(rest) + "\n"


def render_narrative(
    guide_rel: str,
    package: str,
    wrap_fences: list[tuple[int, list[str]]],
    packages: list[str],
    extra_wildcard_imports: tuple[str, ...] = EXTRA_WILDCARD_IMPORTS,
    prelude_bindings: tuple[tuple[str, str], ...] = PRELUDE_BINDINGS,
) -> str:
    """The guide's `wrap`-classified fences, concatenated in order into one `narrative()`.

    Raises `ExtractionError` for a fence that redeclares one of its own `val`/`var` names within
    itself — the one case a single `run { }` around the fence cannot isolate.
    """
    own_imports_all: list[str] = []
    body: list[str] = []
    visible: set[str] = set()

    body.append("    // prelude: the context every guide's narrative may assume")
    for name, expr in prelude_bindings:
        body.append(f"    val {name} = {expr}")
        visible.add(name)
    body.append("")

    for index, fence_lines in wrap_fences:
        own_imports, rest = _split_own_imports(fence_lines)
        own_imports_all.extend(own_imports)

        own_names = depth0_declared_names(rest)
        if len(own_names) != len(set(own_names)):
            raise ExtractionError(
                f"{guide_rel} snippet {index} declares the same top-level name more than once within "
                "itself — a single `run { }` around the fence cannot isolate two declarations in one "
                "scope from each other. Add `<!-- no-compile: <reason> -->` above it instead."
            )
        needs_isolation = bool(set(own_names) & visible)

        body.append(f"    // {guide_rel} snippet {index}")
        if needs_isolation:
            body.append("    run {")
            body.extend(("        " + line if line.strip() else line) for line in rest)
            body.append("    }")
        else:
            body.extend(("    " + line if line.strip() else line) for line in rest)
            visible |= set(own_names)
        body.append("")

    header = _file_header(guide_rel, "narrative", package, packages, own_imports_all, extra_wildcard_imports)
    return header + "private suspend fun narrative() {\n" + "\n".join(body) + "\n}\n"


def build_guide(
    path: Path,
    packages: list[str],
    reserved_names: set[str],
    extra_wildcard_imports: tuple[str, ...] = EXTRA_WILDCARD_IMPORTS,
    prelude_bindings: tuple[tuple[str, str], ...] = PRELUDE_BINDINGS,
    prelude_packages: tuple[str, ...] = (),
) -> tuple[list[tuple[str, str]], list[str]]:
    """Returns (list of (filename, content) to write, list of skip descriptions) for one guide.

    `reserved_names` (from `public_simple_names`): a top-level fence declaring one of these — a
    fence restating `EnrichmentResult`'s own shape, say — gets its own sub-package instead of the
    guide's shared one. Sharing the package would let it shadow the real type for every other fence
    in the guide, per Kotlin's same-package-beats-star-import resolution. The same isolation applies
    when a *later* top-level fence redeclares a name an *earlier* one in the same guide already used
    (two worked-example providers each registering their own top-level `val engine`, say). Nothing
    today needs a top-level fence's declaration visible from a different fence, so isolating the rare
    colliding one costs nothing.
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    guide_rel = f"{GUIDES_DIR}/{path.name}"
    guide_stem = path.stem
    package = guide_package(guide_stem)

    kept: list[tuple[int, list[str]]] = []
    skipped: list[str] = []
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
            kept.append((index, body_lines))
        i = j + 1

    files: list[tuple[str, str]] = []
    wrap_fences: list[tuple[int, list[str]]] = []
    # Two top-level fences declaring the same name — two worked-example providers each registering
    # their own top-level `val engine`, say — collide the same way a reserved name does once both
    # sit in the guide's shared package; tracked here in fence order so only the second one isolates.
    top_level_seen: set[str] = set()
    for snippet_index, body_lines in kept:
        if classify(body_lines) == "top-level":
            filename = f"{guide_stem.replace('-', '_')}_snippet{snippet_index}.kt"
            own_names = set(_depth0_declaration_names(body_lines))
            if own_names & (reserved_names | top_level_seen):
                fence_package = f"{package}.snippet{snippet_index}"
            else:
                fence_package = package
                top_level_seen |= own_names
            files.append(
                (
                    filename,
                    render_top_level_fence(
                        guide_rel,
                        snippet_index,
                        fence_package,
                        body_lines,
                        packages,
                        extra_wildcard_imports,
                        prelude_packages,
                    ),
                )
            )
        else:
            wrap_fences.append((snippet_index, body_lines))

    if wrap_fences:
        filename = f"{guide_stem.replace('-', '_')}_narrative.kt"
        files.append(
            (
                filename,
                render_narrative(guide_rel, package, wrap_fences, packages, extra_wildcard_imports, prelude_bindings),
            )
        )

    return files, skipped


def _write_target(
    out_dir: Path,
    guides: list[Path],
    packages: list[str],
    reserved_names: set[str],
    extra_wildcard_imports: tuple[str, ...],
    prelude_bindings: tuple[tuple[str, str], ...],
    prelude_packages: tuple[str, ...],
) -> tuple[int, int]:
    """Writes every compiled sample from `guides` under `out_dir`, clearing it first. Returns
    (compiled fences, skipped fences) — a fence count, not a file count: a narrative file holds
    several. One target (JVM or Android); `run` calls this once per target."""
    if out_dir.is_dir():
        for stale in out_dir.rglob("*.kt"):
            stale.unlink()
    out_dir.mkdir(parents=True, exist_ok=True)

    total_compiled = 0
    total_skipped = 0
    for guide in guides:
        files, skipped = build_guide(
            guide, packages, reserved_names, extra_wildcard_imports, prelude_bindings, prelude_packages
        )
        for filename, content in files:
            (out_dir / filename).write_text(content, encoding="utf-8")
        # A narrative file holds several fences; count them via their marker comments so the totals
        # printed here mean "fences", the unit the census in the report and VERIFICATION.md use.
        compiled_here = sum(content.count(f"// {GUIDES_DIR}/{guide.name} snippet ") for _, content in files)
        total_compiled += compiled_here
        total_skipped += len(skipped)
    return total_compiled, total_skipped


def run(root: Path, out_dir: Path, android_out_dir: Path) -> tuple[int, int, int, int]:
    """Writes every guide except `android.md` under `out_dir` (the JVM target) and `android.md`
    under `android_out_dir` (the Android target) — one extraction pass, two compiled targets; see
    the module docstring. Returns (jvm compiled, jvm skipped, android compiled, android skipped)."""
    guides_dir = root / GUIDES_DIR
    all_guides = sorted(guides_dir.glob("*.md")) if guides_dir.is_dir() else []
    jvm_guides = [g for g in all_guides if g.name != ANDROID_GUIDE_NAME]
    android_guides = [g for g in all_guides if g.name == ANDROID_GUIDE_NAME]

    jvm_compiled, jvm_skipped = _write_target(
        out_dir,
        jvm_guides,
        public_packages(root),
        public_simple_names(root),
        EXTRA_WILDCARD_IMPORTS,
        PRELUDE_BINDINGS,
        (),
    )
    android_compiled, android_skipped = _write_target(
        android_out_dir,
        android_guides,
        public_packages(root, ANDROID_API_FILES),
        public_simple_names(root, ANDROID_API_FILES),
        ANDROID_EXTRA_WILDCARD_IMPORTS,
        ANDROID_PRELUDE_BINDINGS,
        (ANDROID_PRELUDE_PACKAGE,),
    )
    return jvm_compiled, jvm_skipped, android_compiled, android_skipped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compile docs/guides/*.md Kotlin fences, per guide, into docs-samples/ and docs-samples-android/."
    )
    parser.add_argument("--root", help="repository root (default: inferred from this file)")
    parser.add_argument("--out", help="JVM output directory (default: docs-samples/build/generated-samples/...)")
    parser.add_argument(
        "--android-out", help="Android output directory (default: docs-samples-android/build/generated-samples/...)"
    )
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent.parent
    out_dir = Path(args.out).resolve() if args.out else root / OUT_DIR
    android_out_dir = Path(args.android_out).resolve() if args.android_out else root / ANDROID_OUT_DIR

    try:
        jvm_compiled, jvm_skipped, android_compiled, android_skipped = run(root, out_dir, android_out_dir)
    except ExtractionError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 2

    print(f"{jvm_compiled} doc sample(s) extracted to {out_dir}, {jvm_skipped} opted out.")
    print(f"{android_compiled} doc sample(s) extracted to {android_out_dir}, {android_skipped} opted out.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
