"""Find all usages/references of a symbol in the workspace."""

import subprocess
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "list_code_usages",
    "description": "Find all usages/references of a symbol name in the workspace or specific files.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "symbolName": {"type": "string", "description": "The symbol name to search for."},
            "filePaths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Optional list of file paths to restrict the search to.",
            },
        },
        "required": ["symbolName"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    symbol = tool_input.get("symbolName", "")
    file_paths = tool_input.get("filePaths", [])
    cmd = ["grep", "-rn", "-F", symbol]
    if file_paths:
        # Search only in specified files
        cmd = ["grep", "-n", "-F", symbol] + file_paths
    else:
        cmd.append(ctx.workspace_root)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    output = result.stdout[:6000]
    count = output.count("\n")
    logger.debug("list_code_usages '%s': %d matches", symbol, count)
    return [{"type": "text", "value": output if output else f"No usages found for '{symbol}'."}]
