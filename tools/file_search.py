"""Search for files by name or glob pattern in the workspace."""

import fnmatch
import os
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "file_search",
    "description": "Search for files by name or glob pattern in the workspace.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "Glob pattern or substring to match file names/paths."},
            "maxResults": {"type": "number", "description": "Maximum number of results to return."},
        },
        "required": ["query"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    query = tool_input.get("query", "")
    max_results = tool_input.get("maxResults", 50)
    matches = []
    for root, dirs, files in os.walk(ctx.workspace_root):
        # Skip hidden dirs
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for fname in files:
            full = os.path.join(root, fname)
            rel = os.path.relpath(full, ctx.workspace_root)
            if fnmatch.fnmatch(rel, query) or fnmatch.fnmatch(fname, query) or query.lower() in rel.lower():
                matches.append(rel)
                if len(matches) >= max_results:
                    break
        if len(matches) >= max_results:
            break
    result_text = "\n".join(matches) if matches else "No files found."
    logger.debug("file_search '%s': %d results", query, len(matches))
    return [{"type": "text", "value": result_text}]
