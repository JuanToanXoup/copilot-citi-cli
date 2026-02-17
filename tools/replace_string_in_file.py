"""Replace an exact string match in a file with new content."""

from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "replace_string_in_file",
    "description": "Replace an exact string match in a file with new content.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePath": {"type": "string", "description": "The absolute path of the file to edit."},
            "oldString": {"type": "string", "description": "The exact literal text to replace. Include context lines for uniqueness."},
            "newString": {"type": "string", "description": "The exact literal text to replace oldString with."},
            "explanation": {"type": "string", "description": "A short explanation of the replacement."},
        },
        "required": ["filePath", "oldString", "newString", "explanation"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> dict:
    file_path = tool_input.get("filePath", "")
    old_string = tool_input.get("oldString", "")
    new_string = tool_input.get("newString", "")
    with open(file_path, "r") as f:
        content = f.read()
    if old_string not in content:
        logger.debug("oldString not found in %s", file_path)
        return {"result": "error", "message": "oldString not found in file"}
    content = content.replace(old_string, new_string, 1)
    with open(file_path, "w") as f:
        f.write(content)
    ctx.sync_file_to_server(file_path, content)
    logger.debug("Replaced string in %s", file_path)
    return {"result": "success"}
