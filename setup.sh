#!/usr/bin/env bash
# Setup and run script for copilot-citi-cli.
# Replaces Gradle tasks with plain shell commands.
#
# Usage:
#   ./setup.sh install     Create venv and install dependencies
#   ./setup.sh run [args]  Run the CLI (e.g. ./setup.sh run agent)
#   ./setup.sh builder     Launch the Agent Builder web UI
#   ./setup.sh test        Run pytest
#   ./setup.sh lint        Run ruff
#   ./setup.sh clean       Remove venv and caches

set -euo pipefail
cd "$(dirname "$0")"

VENV_DIR=".venv"
CONFIG="cli/src/copilot_cli/config.toml"

# --- Proxy detection (config.toml [proxy] section, then env vars) ---
detect_proxy() {
    if [ -f "$CONFIG" ]; then
        url=$(awk '/^\[proxy\]/{found=1; next} /^\[/{found=0} found && /^url/{gsub(/.*= *"|"/, ""); print; exit}' "$CONFIG")
        if [ -n "$url" ]; then echo "$url"; return; fi
    fi
    echo "${HTTPS_PROXY:-${HTTP_PROXY:-}}"
}

PROXY_URL=$(detect_proxy)
PIP_PROXY=()
if [ -n "$PROXY_URL" ]; then
    PIP_PROXY=(--proxy "$PROXY_URL")
    echo "[*] Using proxy: $PROXY_URL"
fi

# --- Find Python >= 3.10 ---
find_python() {
    for name in python3.13 python3.12 python3.11 python3.10; do
        for dir in /opt/local/bin /usr/local/bin /opt/homebrew/bin ""; do
            cmd="${dir:+$dir/}$name"
            if command -v "$cmd" &>/dev/null; then
                ver=$("$cmd" -c 'import sys; print(sys.version_info.minor)' 2>/dev/null || echo 0)
                if [ "$ver" -ge 10 ]; then echo "$cmd"; return; fi
            fi
        done
    done
    # Fallback
    if command -v python3 &>/dev/null; then
        ver=$(python3 -c 'import sys; print(sys.version_info.minor)' 2>/dev/null || echo 0)
        if [ "$ver" -ge 10 ]; then echo "python3"; return; fi
    fi
    echo "Error: Python >= 3.10 not found" >&2; exit 1
}

# --- Commands ---
cmd_install() {
    if [ ! -d "$VENV_DIR" ]; then
        PYTHON=$(find_python)
        echo "[*] Creating venv with $($PYTHON --version)..."
        "$PYTHON" -m venv "$VENV_DIR"
    fi
    echo "[*] Upgrading pip..."
    "$VENV_DIR/bin/python" -m pip install --upgrade pip setuptools "${PIP_PROXY[@]}" -q
    echo "[*] Installing modules..."
    "$VENV_DIR/bin/pip" install \
        -e cli/ \
        -e agent-builder/ \
        pytest ruff \
        "${PIP_PROXY[@]}" -q
    echo "[*] Done. Run with: .venv/bin/copilot agent"
}

cmd_run() {
    [ -d "$VENV_DIR" ] || cmd_install
    "$VENV_DIR/bin/python" -m copilot_cli "$@"
}

cmd_builder() {
    [ -d "$VENV_DIR" ] || cmd_install
    "$VENV_DIR/bin/python" -m agent_builder "$@"
}

cmd_test() {
    [ -d "$VENV_DIR" ] || cmd_install
    "$VENV_DIR/bin/python" -m pytest tests/ -v
}

cmd_lint() {
    [ -d "$VENV_DIR" ] || cmd_install
    "$VENV_DIR/bin/python" -m ruff check cli/src agent-builder/src tests/
}

cmd_clean() {
    echo "[*] Cleaning..."
    rm -rf "$VENV_DIR" build/
    find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
    find . -type d -name '*.egg-info' -exec rm -rf {} + 2>/dev/null || true
    find . -type d -name .pytest_cache -exec rm -rf {} + 2>/dev/null || true
    echo "[*] Clean."
}

# --- Dispatch ---
case "${1:-install}" in
    install)  cmd_install ;;
    run)      shift; cmd_run "$@" ;;
    builder)  shift; cmd_builder "$@" ;;
    test)     cmd_test ;;
    lint)     cmd_lint ;;
    clean)    cmd_clean ;;
    *)        echo "Usage: $0 {install|run|builder|test|lint|clean}"; exit 1 ;;
esac
