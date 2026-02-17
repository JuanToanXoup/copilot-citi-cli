"""Create a new file with the given content."""

import os
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "create_file",
    "description": "Create a new file with the given content.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePath": {"type": "string", "description": "The absolute path for the new file."},
            "content": {"type": "string", "description": "The content of the new file."},
        },
        "required": ["filePath", "content"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> dict:
    file_path = tool_input.get("filePath", "")
    content = tool_input.get("content", "")
    os.makedirs(os.path.dirname(file_path) or ".", exist_ok=True)
    with open(file_path, "w") as f:
        f.write(content)
    ctx.sync_file_to_server(file_path, content)
    logger.debug("Created file %s (%d chars)", file_path, len(content))
    return {"result": "success"}
