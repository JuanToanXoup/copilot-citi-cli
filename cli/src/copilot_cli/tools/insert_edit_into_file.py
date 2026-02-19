"""Insert or replace text in a file. Creates the file if it doesn't exist."""

import os
from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "insert_edit_into_file",
    "description": "Insert or replace text in a file. Creates the file if it doesn't exist.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePath": {"type": "string", "description": "The absolute path of the file to edit."},
            "code": {"type": "string", "description": "The new code to insert."},
            "explanation": {"type": "string", "description": "A short explanation of what this edit does."},
        },
        "required": ["filePath", "code"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    file_path = tool_input.get("filePath", "")
    code = tool_input.get("code", "")
    explanation = tool_input.get("explanation", "")
    os.makedirs(os.path.dirname(file_path) or ".", exist_ok=True)
    with open(file_path, "w") as f:
        f.write(code)
    ctx.sync_file_to_server(file_path, code)
    logger.debug("Wrote %d chars to %s: %s", len(code), file_path, explanation)
    return [{"type": "text", "value": f"Edited file {file_path}"}]
