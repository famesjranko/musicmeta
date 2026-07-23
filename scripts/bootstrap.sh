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
MYPY_VERSION="2.3.0"
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

# Exact version token, not a substring: a plain `grep 0.11.3` is satisfied by 0.11.31, so a pin
# could silently accept the wrong build.
version_is() { # version_is <command> <expected-version>
    command -v "$1" >/dev/null 2>&1 || return 1
    "$1" --version 2>/dev/null | grep -qE "(^|[^0-9.])${2//./\\.}([^0-9.]|\$)"
}

# --- uv: installs the Python tools without needing a system pip (there is not one here) ---
if version_is uv "$UV_VERSION"; then
    echo "uv $UV_VERSION already installed"
else
    echo "installing uv $UV_VERSION"
    curl -fsSL -o "$tmp/uv.tar.gz" \
        "https://github.com/astral-sh/uv/releases/download/${UV_VERSION}/uv-x86_64-unknown-linux-gnu.tar.gz"
    tar xzf "$tmp/uv.tar.gz" -C "$tmp"
    install -m 755 "$tmp/uv-x86_64-unknown-linux-gnu/uv" "$BIN_DIR/uv"
fi
# Resolve after the branch. An already-satisfied uv may live anywhere on PATH — the official
# installer puts it in ~/.cargo/bin — so assuming "$BIN_DIR/uv" aborts the run for anyone who
# already had it.
UV="$(command -v uv || echo "$BIN_DIR/uv")"

# --- ruff: formatter and linter for the Python under scripts/ ---
if version_is ruff "$RUFF_VERSION"; then
    echo "ruff $RUFF_VERSION already installed"
else
    echo "installing ruff $RUFF_VERSION"
    "$UV" tool install --force "ruff@${RUFF_VERSION}" >/dev/null
fi

# --- mypy: the type-checker layer for those same scripts ---
if version_is mypy "$MYPY_VERSION"; then
    echo "mypy $MYPY_VERSION already installed"
else
    echo "installing mypy $MYPY_VERSION"
    "$UV" tool install --force "mypy==${MYPY_VERSION}" >/dev/null
fi

# --- shellcheck: ./check and the release scripts are shell, and were the last unchecked code ---
if version_is shellcheck "$SHELLCHECK_VERSION"; then
    echo "shellcheck $SHELLCHECK_VERSION already installed"
else
    echo "installing shellcheck $SHELLCHECK_VERSION"
    curl -fsSL -o "$tmp/sc.tar.xz" \
        "https://github.com/koalaman/shellcheck/releases/download/v${SHELLCHECK_VERSION}/shellcheck-v${SHELLCHECK_VERSION}.linux.x86_64.tar.xz"
    tar xJf "$tmp/sc.tar.xz" -C "$tmp"
    install -m 755 "$tmp/shellcheck-v${SHELLCHECK_VERSION}/shellcheck" "$BIN_DIR/shellcheck"
fi

# --- ktlint CLI: optional. Only the format-on-write hook uses it; ktlintCheck is the real gate. ---
if version_is ktlint "$KTLINT_VERSION"; then
    echo "ktlint $KTLINT_VERSION already installed"
else
    echo "installing ktlint $KTLINT_VERSION (optional — powers format-on-write)"
    curl -fsSL -o "$tmp/ktlint" \
        "https://github.com/pinterest/ktlint/releases/download/${KTLINT_VERSION}/ktlint"
    install -m 755 "$tmp/ktlint" "$BIN_DIR/ktlint"
fi

echo
echo "done. run ./check"
