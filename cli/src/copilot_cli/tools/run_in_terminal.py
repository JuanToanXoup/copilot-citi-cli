"""Run a shell command in the terminal."""

import subprocess
from copilot_cli.tools._base import ToolContext, TOOL_OUTPUT_LIMIT
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "run_in_terminal",
    "description": "Run a shell command in the terminal.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "command": {"type": "string", "description": "The command to run."},
            "explanation": {"type": "string", "description": "What this command does."},
        },
        "required": ["command", "explanation"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> dict:
    command = tool_input.get("command", "")
    explanation = tool_input.get("explanation", "")
    logger.debug("Terminal: %s (%s)", command, explanation)
    result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=60,
                            cwd=ctx.workspace_root)
    output = result.stdout + result.stderr
    logger.debug("Exit code: %d, output: %s", result.returncode, output[:200])
    return {"result": "success", "output": output[:TOOL_OUTPUT_LIMIT]}
