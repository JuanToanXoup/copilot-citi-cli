"""Check files for syntax/compile errors."""

import subprocess
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "get_errors",
    "description": "Check files for syntax/compile errors.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePaths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "File paths to check. Omit for all files.",
            },
        },
        "required": [],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    file_paths = tool_input.get("filePaths", [])
    errors = []
    targets = file_paths if file_paths else [ctx.workspace_root]
    for fp in targets:
        if fp.endswith(".py"):
            result = subprocess.run(
                ["python3", "-m", "py_compile", fp],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode != 0:
                errors.append(result.stderr.strip())
    result_text = "\n".join(errors) if errors else "No errors found."
    logger.debug("get_errors: %d issues", len(errors))
    return [{"type": "text", "value": result_text}]
