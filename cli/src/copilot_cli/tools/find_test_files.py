"""Find test files associated with the given source files."""

import os
from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "find_test_files",
    "description": "Find test files associated with the given source files.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePaths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Source file paths to find tests for.",
            },
        },
        "required": ["filePaths"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    file_paths = tool_input.get("filePaths", [])
    test_files = []
    for fp in file_paths:
        base = os.path.basename(fp)
        name, ext = os.path.splitext(base)
        patterns = [f"test_{name}{ext}", f"{name}_test{ext}", f"tests/{base}",
                    f"test/{base}", f"tests/test_{name}{ext}"]
        d = os.path.dirname(fp)
        for pat in patterns:
            candidate = os.path.join(d, pat)
            if os.path.exists(candidate):
                test_files.append(candidate)
        # Also search workspace
        for root, _, files in os.walk(ctx.workspace_root):
            for f in files:
                if f in [f"test_{name}{ext}", f"{name}_test{ext}"]:
                    test_files.append(os.path.join(root, f))
    result_text = "\n".join(set(test_files)) if test_files else "No test files found."
    logger.debug("find_test_files: %d found", len(test_files))
    return [{"type": "text", "value": result_text}]
