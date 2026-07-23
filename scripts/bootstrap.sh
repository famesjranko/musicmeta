#!/usr/bin/env bash
# Install the pinned tools ./check requires. Idempotent — re-running is a no-op when versions match.
#
# Versions are pinned rather than tracking latest so a toolchain update is a reviewed commit here
# instead of a CI failure nobody changed anything to cause.
#
# Installs into ~/.local/bin (override with BIN_DIR). Kotlin tooling comes from Gradle and needs
# nothing here; ktlint's CLI is optional and only powers the format-on-write hook.
set -euo pipefail

UV_VERSION="0.11.31"
RUFF_VERSION="0.15.22"
SHELLCHECK_VERSION="0.11.0"
KTLINT_VERSION="1.8.0"

BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
mkdir -p "$BIN_DIR"

case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo "warning: $BIN_DIR is not on PATH — add it or ./check will not find these" >&2 ;;
esac

ARCH="$(uname -m)"
if [ "$ARCH" != "x86_64" ] && [ "$ARCH" != "amd64" ]; then
    echo "error: this script only handles x86_64; install ruff/mypy/shellcheck/ktlint by hand" >&2
    exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

have() { command -v "$1" >/dev/null 2>&1; }

# --- uv: installs the Python tools without needing a system pip (there is not one here) ---
if ! have uv || ! uv --version 2>/dev/null | grep -q "$UV_VERSION"; then
    echo "installing uv $UV_VERSION"
    curl -fsSL -o "$tmp/uv.tar.gz" \
        "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz"
    tar xzf "$tmp/uv.tar.gz" -C "$tmp"
    install -m 755 "$tmp/uv-x86_64-unknown-linux-gnu/uv" "$BIN_DIR/uv"
else
    echo "uv $UV_VERSION already installed"
fi

# --- ruff: formatter and linter for the Python under scripts/ ---
if ! have ruff || ! ruff --version 2>/dev/null | grep -q "$RUFF_VERSION"; then
    echo "installing ruff $RUFF_VERSION"
    "$BIN_DIR/uv" tool install --force "ruff@${RUFF_VERSION}" >/dev/null
else
    echo "ruff $RUFF_VERSION already installed"
fi

# --- mypy: the type-checker layer for those same scripts ---
if ! have mypy; then
    echo "installing mypy"
    "$BIN_DIR/uv" tool install --force mypy >/dev/null
else
    echo "mypy already installed ($(mypy --version))"
fi

# --- shellcheck: ./check and the release scripts are shell, and were the last unchecked code ---
if ! have shellcheck || ! shellcheck --version 2>/dev/null | grep -q "$SHELLCHECK_VERSION"; then
    echo "installing shellcheck $SHELLCHECK_VERSION"
    curl -fsSL -o "$tmp/sc.tar.xz" \
        "https://github.com/koalaman/shellcheck/releases/download/v${SHELLCHECK_VERSION}/shellcheck-v${SHELLCHECK_VERSION}.linux.x86_64.tar.xz"
    tar xJf "$tmp/sc.tar.xz" -C "$tmp"
    install -m 755 "$tmp/shellcheck-v${SHELLCHECK_VERSION}/shellcheck" "$BIN_DIR/shellcheck"
else
    echo "shellcheck $SHELLCHECK_VERSION already installed"
fi

# --- ktlint CLI: optional. Only the format-on-write hook uses it; ktlintCheck is the real gate. ---
if ! have ktlint || ! ktlint --version 2>/dev/null | grep -q "$KTLINT_VERSION"; then
    echo "installing ktlint $KTLINT_VERSION (optional — powers format-on-write)"
    curl -fsSL -o "$tmp/ktlint" \
        "https://github.com/pinterest/ktlint/releases/download/${KTLINT_VERSION}/ktlint"
    install -m 755 "$tmp/ktlint" "$BIN_DIR/ktlint"
else
    echo "ktlint $KTLINT_VERSION already installed"
fi

echo
echo "done. run ./check"
