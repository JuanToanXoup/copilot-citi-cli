#!/usr/bin/env bash
set -euo pipefail

# Headless IntelliJ MCP Server launcher for stdio transport.
# Usage: headless-idea.sh /path/to/project

PROJECT_PATH="${1:?Usage: headless-idea.sh <project-path>}"

# shellcheck source=idea-common.sh
source "$(cd "$(dirname "$0")" && pwd)/idea-common.sh"

exec "$JBR/bin/java" \
  "${COMMON_JVM_ARGS[@]}" \
  -Dsplash=false -Djava.awt.headless=true \
  com.intellij.idea.Main \
  mcp-stdio "$PROJECT_PATH"
