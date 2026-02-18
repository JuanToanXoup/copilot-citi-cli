"""Persistent memory store for the agent — save/read/list/delete notes."""

import os
from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

_MEMORY_DIR = os.path.expanduser("~/.copilot-cli/memories")

SCHEMA = {
    "name": "memory",
    "description": "Persistent memory store. Save, read, list, or delete named memory files for cross-session recall.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "command": {
                "type": "string",
                "description": "The operation: 'save', 'read', 'list', or 'delete'.",
                "enum": ["save", "read", "list", "delete"],
            },
            "path": {
                "type": "string",
                "description": "Memory file name (e.g. 'project-notes.md'). Required for save/read/delete.",
            },
            "content": {
                "type": "string",
                "description": "Content to save. Required for 'save' command.",
            },
        },
        "required": ["command"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    command = tool_input.get("command", "")
    path = tool_input.get("path", "")
    content = tool_input.get("content", "")

    os.makedirs(_MEMORY_DIR, exist_ok=True)

    if command == "list":
        entries = []
        for f in sorted(os.listdir(_MEMORY_DIR)):
            full = os.path.join(_MEMORY_DIR, f)
            if os.path.isfile(full):
                size = os.path.getsize(full)
                entries.append(f"{f} ({size} bytes)")
        listing = "\n".join(entries) if entries else "No memories saved yet."
        logger.debug("memory list: %d entries", len(entries))
        return [{"type": "text", "value": listing}]

    if not path:
        return [{"type": "text", "value": "Error: 'path' is required for save/read/delete."}]

    # Sanitize path to prevent directory traversal
    safe_name = os.path.basename(path)
    full_path = os.path.join(_MEMORY_DIR, safe_name)

    if command == "save":
        with open(full_path, "w") as f:
            f.write(content)
        logger.debug("memory save: %s (%d chars)", safe_name, len(content))
        return [{"type": "text", "value": f"Saved memory '{safe_name}'."}]

    elif command == "read":
        if not os.path.exists(full_path):
            return [{"type": "text", "value": f"Memory '{safe_name}' not found."}]
        with open(full_path, "r") as f:
            data = f.read()
        logger.debug("memory read: %s (%d chars)", safe_name, len(data))
        return [{"type": "text", "value": data}]

    elif command == "delete":
        if os.path.exists(full_path):
            os.remove(full_path)
            logger.debug("memory delete: %s", safe_name)
            return [{"type": "text", "value": f"Deleted memory '{safe_name}'."}]
        return [{"type": "text", "value": f"Memory '{safe_name}' not found."}]

    return [{"type": "text", "value": f"Unknown memory command: {command}"}]
