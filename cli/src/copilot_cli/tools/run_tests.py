"""Run tests using the project's test framework."""

import subprocess
from copilot_cli.tools._base import ToolContext, TOOL_OUTPUT_LIMIT
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "run_tests",
    "description": "Run tests using the project's test framework.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "command": {"type": "string", "description": "The test command to run."},
            "explanation": {"type": "string", "description": "What tests are being run."},
        },
        "required": ["command"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    command = tool_input.get("command", "")
    explanation = tool_input.get("explanation", "")
    logger.debug("Running tests: %s (%s)", command, explanation)
    result = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=120,
                            cwd=ctx.workspace_root)
    output = result.stdout + result.stderr
    logger.debug("Tests exit code: %d, output: %s", result.returncode, output[:300])
    text = output[:TOOL_OUTPUT_LIMIT] if output.strip() else f"Tests exited with code {result.returncode}"
    return [{"type": "text", "value": f"Exit code: {result.returncode}\n{text}"}]
