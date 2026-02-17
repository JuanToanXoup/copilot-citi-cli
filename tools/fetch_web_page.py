"""Fetch content from one or more URLs."""

import urllib.request
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "fetch_web_page",
    "description": "Fetch content from one or more URLs.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "urls": {
                "type": "array",
                "items": {"type": "string"},
                "description": "URLs to fetch content from.",
            },
            "query": {"type": "string", "description": "What to look for in the page content."},
        },
        "required": ["urls", "query"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    urls = tool_input.get("urls", [])
    results = []
    for url in urls[:5]:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "CopilotCLI/0.1"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                body = resp.read().decode("utf-8", errors="replace")[:8000]
            results.append(f"## {url}\n{body}")
            logger.debug("Fetched %s (%d chars)", url, len(body))
        except Exception as e:
            results.append(f"## {url}\nError: {e}")
    return [{"type": "text", "value": "\n\n".join(results)}]
