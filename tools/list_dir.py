"""List the contents of a directory."""

import os
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "list_dir",
    "description": "List the contents of a directory.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "The absolute path to the directory to list."},
        },
        "required": ["path"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    dir_path = tool_input.get("path", "")
    entries = []
    for entry in sorted(os.listdir(dir_path)):
        full = os.path.join(dir_path, entry)
        if os.path.isdir(full):
            entries.append(f"[dir]  {entry}")
        else:
            size = os.path.getsize(full)
            entries.append(f"[file] {entry} ({size} bytes)")
    listing = "\n".join(entries)
    logger.debug("Listed %s (%d entries)", dir_path, len(entries))
    return [{"type": "text", "value": listing}]
