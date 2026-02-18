#!/usr/bin/env bash
set -euo pipefail

# GUI IntelliJ launcher with the MCP plugin loaded.
# Usage: headful-idea.sh [/path/to/project]
# The MCP SSE server starts automatically via the plugin's startup activity.

PROJECT_PATH="${1:-}"

# shellcheck source=idea-common.sh
source "$(cd "$(dirname "$0")" && pwd)/idea-common.sh"

ARGS=(
  "$JBR/bin/java"
  "${COMMON_JVM_ARGS[@]}"
  com.intellij.idea.Main
)

# Open project if provided
if [ -n "$PROJECT_PATH" ]; then
  ARGS+=("$PROJECT_PATH")
fi

exec "${ARGS[@]}"
