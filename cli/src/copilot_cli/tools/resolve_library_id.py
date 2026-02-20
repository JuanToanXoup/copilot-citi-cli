"""Resolve a library name to a library ID for use with get_library_docs.

Step 1 of the two-step doc lookup pattern: search for libraries by name,
return matches with IDs that can be passed to get_library_docs.

Searches local bundled docs first (Playwright, Selenium, Cucumber, Gherkin,
Java). Falls back to Context7 API for other libraries when reachable.
"""

import json
import urllib.request
import urllib.parse
from copilot_cli.tools._base import ToolContext, TOOL_OUTPUT_LIMIT
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "resolve_library_id",
    "description": (
        "Resolves a library/package name to a library ID. "
        "Call this BEFORE get_library_docs to find the correct library ID. "
        "Bundled docs: Playwright, Selenium, Cucumber, Gherkin, Java. "
        "Other libraries are looked up via Context7 API (when reachable)."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "libraryName": {
                "type": "string",
                "description": (
                    "Library name to search for (e.g. 'playwright', 'selenium', "
                    "'cucumber', 'gherkin', 'java')."
                ),
            },
            "query": {
                "type": "string",
                "description": (
                    "The user's question or task. Used to rank results by relevance."
                ),
            },
        },
        "required": ["libraryName"],
    },
}

_CONTEXT7_API = "https://context7.com/api/v2"
_TIMEOUT = 10


def execute(tool_input: dict, ctx: ToolContext) -> list:
    library_name = tool_input.get("libraryName", "")
    query = tool_input.get("query", library_name)

    if not library_name:
        return [{"type": "text", "value": "Error: 'libraryName' is required."}]

    # --- Step 1: Search local bundled docs ---
    from copilot_cli.library_docs import resolve
    local_matches = resolve(library_name)

    if local_matches:
        lines = []
        for meta in local_matches:
            lines.append(
                f"- **{meta['title']}**\n"
                f"  ID: `{meta['id']}`\n"
                f"  {meta['description']}\n"
                f"  Source: bundled (always available)"
            )
        output = "\n\n".join(lines)
        logger.debug("resolve_library_id: '%s' → %d local matches", library_name, len(local_matches))
        return [{"type": "text", "value": output}]

    # --- Step 2: Fallback to Context7 API ---
    params = urllib.parse.urlencode({"query": query, "libraryName": library_name})
    url = f"{_CONTEXT7_API}/libs/search?{params}"

    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": "CopilotCLI/0.1",
            "X-Context7-Source": "copilot-cli",
        })
        with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        logger.debug("Context7 API unreachable: %s", e)
        return [{"type": "text", "value": (
            f"No bundled docs for '{library_name}' and Context7 API is unreachable.\n"
            f"Bundled libraries: playwright, selenium, cucumber, gherkin, java."
        )}]

    results = data.get("results", data) if isinstance(data, dict) else data
    if not results:
        return [{"type": "text", "value": f"No libraries found matching '{library_name}'."}]

    lines = []
    for item in results[:10]:
        title = item.get("title", "")
        lib_id = item.get("id", "")
        desc = item.get("description", "")
        entry = f"- **{title}**\n  ID: `{lib_id}`"
        if desc:
            entry += f"\n  {desc}"
        entry += "\n  Source: Context7 API"
        lines.append(entry)

    output = "\n\n".join(lines)
    if len(output) > TOOL_OUTPUT_LIMIT:
        output = output[:TOOL_OUTPUT_LIMIT] + "\n... (truncated)"

    logger.debug("resolve_library_id: '%s' → %d Context7 results", library_name, len(results))
    return [{"type": "text", "value": output}]
