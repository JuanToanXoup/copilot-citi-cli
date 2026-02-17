"""Create a new directory (and parent directories as needed)."""

import os
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "create_directory",
    "description": "Create a new directory (and parent directories as needed).",
    "inputSchema": {
        "type": "object",
        "properties": {
            "dirPath": {"type": "string", "description": "The absolute path of the directory to create."},
        },
        "required": ["dirPath"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> dict:
    dir_path = tool_input.get("dirPath", "")
    os.makedirs(dir_path, exist_ok=True)
    logger.debug("Created directory %s", dir_path)
    return {"result": "success"}
