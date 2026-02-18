"""Get list of changed files from git status/diff."""

import subprocess
from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "get_changed_files",
    "description": "Get the list of changed, staged, or untracked files from git.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "repositoryPath": {
                "type": "string",
                "description": "Path to the git repository. Defaults to workspace root.",
            },
            "sourceControlState": {
                "type": "string",
                "description": "Filter by state: 'all' (default), 'staged', 'unstaged', 'untracked'.",
            },
        },
        "required": [],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    repo_path = tool_input.get("repositoryPath", ctx.workspace_root)
    state = tool_input.get("sourceControlState", "all")

    results = []

    if state in ("all", "staged"):
        r = subprocess.run(
            ["git", "diff", "--name-only", "--cached"],
            capture_output=True, text=True, timeout=15, cwd=repo_path,
        )
        if r.stdout.strip():
            results.append(f"## Staged files\n{r.stdout.strip()}")

    if state in ("all", "unstaged"):
        r = subprocess.run(
            ["git", "diff", "--name-only"],
            capture_output=True, text=True, timeout=15, cwd=repo_path,
        )
        if r.stdout.strip():
            results.append(f"## Unstaged changes\n{r.stdout.strip()}")

    if state in ("all", "untracked"):
        r = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            capture_output=True, text=True, timeout=15, cwd=repo_path,
        )
        if r.stdout.strip():
            results.append(f"## Untracked files\n{r.stdout.strip()}")

    output = "\n\n".join(results) if results else "No changed files found (or not a git repository)."
    logger.debug("get_changed_files (%s): %d sections", state, len(results))
    return [{"type": "text", "value": output}]
