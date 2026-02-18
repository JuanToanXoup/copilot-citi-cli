"""Search for a text pattern or regex in files within the workspace."""

import fnmatch
import os
import re
import subprocess

from copilot_cli.platform_utils import find_grep
from copilot_cli.tools._base import ToolContext, TOOL_OUTPUT_LIMIT
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "grep_search",
    "description": "Search for a text pattern or regex in files within the workspace.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "The pattern to search for."},
            "isRegexp": {"type": "boolean", "description": "Whether the pattern is a regex. Default: false."},
            "includePattern": {"type": "string", "description": "Glob pattern to filter which files to search."},
        },
        "required": ["query"],
    },
}


def _python_grep(query: str, is_regexp: bool, include: str, root: str) -> str:
    """Pure-Python fallback when grep is not available."""
    pattern = re.compile(query if is_regexp else re.escape(query))
    lines = []
    for dirpath, _, filenames in os.walk(root):
        for fname in filenames:
            if include and not fnmatch.fnmatch(fname, include):
                continue
            fpath = os.path.join(dirpath, fname)
            try:
                with open(fpath, "r", errors="replace") as f:
                    for lineno, line in enumerate(f, 1):
                        if pattern.search(line):
                            lines.append(f"{fpath}:{lineno}:{line.rstrip()}")
                            if len("\n".join(lines)) > TOOL_OUTPUT_LIMIT:
                                return "\n".join(lines)
            except (OSError, UnicodeDecodeError):
                continue
    return "\n".join(lines)


def execute(tool_input: dict, ctx: ToolContext) -> list:
    query = tool_input.get("query", "")
    is_regexp = tool_input.get("isRegexp", False)
    include = tool_input.get("includePattern", "")

    grep_bin = find_grep()
    if grep_bin:
        cmd = [grep_bin, "-rn"]
        if not is_regexp:
            cmd.append("-F")
        if include:
            cmd.extend(["--include", include])
        cmd.extend([query, ctx.workspace_root])
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        output = result.stdout[:TOOL_OUTPUT_LIMIT]
    else:
        output = _python_grep(query, is_regexp, include, ctx.workspace_root)

    count = output.count("\n")
    logger.debug("grep_search '%s': %d matches", query, count)
    return [{"type": "text", "value": output if output else "No matches found."}]
