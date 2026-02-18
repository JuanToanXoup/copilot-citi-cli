"""Read the contents of a file, optionally specifying a line range."""

from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "read_file",
    "description": "Read the contents of a file, optionally specifying a line range.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePath": {"type": "string", "description": "The absolute path of the file to read."},
            "startLineNumberBaseOne": {"type": "number", "description": "Start line (1-based). Default: 1."},
            "endLineNumberBaseOne": {"type": "number", "description": "End line inclusive (1-based). Default: end of file."},
        },
        "required": ["filePath"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    file_path = tool_input.get("filePath", "")
    start = tool_input.get("startLineNumberBaseOne", 1)
    end = tool_input.get("endLineNumberBaseOne", None)
    with open(file_path, "r") as f:
        lines = f.readlines()
    total = len(lines)
    if end is None:
        end = total
    selected = lines[max(0, start - 1):end]
    text = "".join(selected)
    logger.debug("Read %s lines %s-%s (%d chars)", file_path, start, end, len(text))
    return [{"type": "text", "value": f"File `{file_path}`. Total {total} lines. "
             f"Line range (1-based) {start} to {end}:\n```\n{text}\n```"}]
