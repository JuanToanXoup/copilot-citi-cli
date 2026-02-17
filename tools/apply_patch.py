"""Apply a unified diff patch to files."""

import subprocess
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "apply_patch",
    "description": "Apply a unified diff patch to files.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "input": {"type": "string", "description": "The patch content to apply."},
            "explanation": {"type": "string", "description": "A short description of what the patch does."},
        },
        "required": ["input", "explanation"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> dict:
    patch_text = tool_input.get("input", "")
    explanation = tool_input.get("explanation", "")
    logger.debug("Applying patch: %s", explanation)
    result = subprocess.run(
        ["patch", "-p0", "--no-backup-if-mismatch"],
        input=patch_text, capture_output=True, text=True, timeout=30,
        cwd=ctx.workspace_root,
    )
    output = result.stdout + result.stderr
    if result.returncode != 0:
        logger.debug("Patch failed: %s", output[:300])
        return {"result": "error", "message": output[:2000]}
    logger.debug("Patch applied: %s", output[:200])
    return {"result": "success", "output": output[:2000]}
