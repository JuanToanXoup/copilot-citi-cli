"""Auto-discovers tool modules and exports TOOL_SCHEMAS and TOOL_EXECUTORS."""

import importlib
import os
import pkgutil

from copilot_cli.tools._base import ToolContext, ToolResult

TOOL_SCHEMAS: dict[str, dict] = {}
TOOL_EXECUTORS: dict[str, callable] = {}

# ── Tool response format ────────────────────────────────────────────────────
#
# ALL tools return: [{"type": "text", "value": "..."}]
#
# Every tool is registered with the server via conversation/registerTools.
# The client auto-wraps all results (except client-side MCP) into the tuple
# format: [{"content": [{"value": "..."}], "status": "success"}, None]
# via CopilotClient._wrap_registered_tool_result().
#
# See tools/_base.py for the full contract.
# ─────────────────────────────────────────────────────────────────────────────

# Names that the server recognises as "shared" built-in tools.  Kept for
# reference only — all tools are now registered explicitly by the client.
BUILTIN_TOOL_NAMES: set[str] = {
    "insert_edit_into_file", "replace_string_in_file", "multi_replace_string",
    "create_file", "create_directory", "apply_patch", "read_file", "list_dir",
    "file_search", "grep_search", "find_test_files", "run_in_terminal",
    "run_tests", "fetch_web_page", "get_errors",
}

# Scan this package for modules that expose SCHEMA + execute
_pkg_dir = os.path.dirname(__file__)
for _finder, _name, _ispkg in pkgutil.iter_modules([_pkg_dir]):
    if _name.startswith("_"):
        continue
    _mod = importlib.import_module(f"copilot_cli.tools.{_name}")
    _schema = getattr(_mod, "SCHEMA", None)
    _execute = getattr(_mod, "execute", None)
    if _schema and _execute:
        TOOL_SCHEMAS[_schema["name"]] = _schema
        TOOL_EXECUTORS[_schema["name"]] = _execute

__all__ = ["TOOL_SCHEMAS", "TOOL_EXECUTORS", "BUILTIN_TOOL_NAMES", "ToolContext", "ToolResult"]
