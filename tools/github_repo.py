"""Search code in a GitHub repository using the gh CLI."""

import subprocess
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "github_repo",
    "description": "Search code in a GitHub repository using the GitHub CLI (gh).",
    "inputSchema": {
        "type": "object",
        "properties": {
            "repo": {"type": "string", "description": "The GitHub repository in 'owner/repo' format."},
            "query": {"type": "string", "description": "The search query for code search."},
        },
        "required": ["repo", "query"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    repo = tool_input.get("repo", "")
    query = tool_input.get("query", "")
    cmd = ["gh", "search", "code", query, "--repo", repo, "--limit", "20"]
    logger.debug("github_repo: searching '%s' in %s", query, repo)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    output = result.stdout[:6000]
    if result.returncode != 0:
        error = result.stderr.strip()
        logger.debug("github_repo error: %s", error[:200])
        return [{"type": "text", "value": f"Error searching GitHub: {error[:2000]}"}]
    logger.debug("github_repo: got results")
    return [{"type": "text", "value": output if output else "No results found."}]
