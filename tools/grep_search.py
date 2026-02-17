"""Search for a text pattern or regex in files within the workspace."""

import subprocess
from tools._base import ToolContext, TOOL_OUTPUT_LIMIT
from log import get_logger

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


def execute(tool_input: dict, ctx: ToolContext) -> list:
    query = tool_input.get("query", "")
    is_regexp = tool_input.get("isRegexp", False)
    include = tool_input.get("includePattern", "")
    cmd = ["grep", "-rn"]
    if not is_regexp:
        cmd.append("-F")
    if include:
        cmd.extend(["--include", include])
    cmd.extend([query, ctx.workspace_root])
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    output = result.stdout[:TOOL_OUTPUT_LIMIT]
    count = output.count("\n")
    logger.debug("grep_search '%s': %d matches", query, count)
    return [{"type": "text", "value": output if output else "No matches found."}]
