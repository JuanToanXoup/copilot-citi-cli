"""Fetch library documentation relevant to a query.

Step 2 of the two-step doc lookup pattern: given a library ID from
resolve_library_id, search documentation and return relevant sections.

Searches local bundled docs first (Playwright, Selenium, Cucumber, Gherkin,
Java). Falls back to Context7 API for other libraries when reachable.
"""

import urllib.request
import urllib.parse
from copilot_cli.tools._base import ToolContext, TOOL_OUTPUT_LIMIT
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "get_library_docs",
    "description": (
        "Fetches documentation and code examples for a library. "
        "You MUST call resolve_library_id first to get the library ID. "
        "Returns focused documentation sections relevant to your query. "
        "Do not call more than 3 times per question — narrow your query instead."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "libraryId": {
                "type": "string",
                "description": (
                    "Library ID from resolve_library_id "
                    "(e.g. 'playwright', 'selenium', 'cucumber', 'gherkin', 'java', "
                    "or a Context7 ID like '/microsoft/playwright')."
                ),
            },
            "query": {
                "type": "string",
                "description": (
                    "Specific question or task. Be detailed for better results. "
                    "Good: 'How to wait for element to be visible in Playwright'. "
                    "Bad: 'wait'."
                ),
            },
        },
        "required": ["libraryId", "query"],
    },
}

_CONTEXT7_API = "https://context7.com/api/v2"
_TIMEOUT = 15


def execute(tool_input: dict, ctx: ToolContext) -> list:
    library_id = tool_input.get("libraryId", "").strip()
    query = tool_input.get("query", "")

    if not library_id:
        return [{"type": "text", "value": "Error: 'libraryId' is required. Call resolve_library_id first."}]
    if not query:
        return [{"type": "text", "value": "Error: 'query' is required. Describe what you need."}]

    # --- Step 1: Try local bundled docs ---
    from copilot_cli.library_docs import search_docs, LIBRARIES

    # Normalize: strip leading / and extract last path component for Context7 IDs
    local_id = library_id.strip("/").split("/")[-1].lower() if "/" in library_id else library_id.lower()

    if local_id in LIBRARIES:
        result = search_docs(local_id, query, max_chars=TOOL_OUTPUT_LIMIT)
        if result:
            logger.debug("get_library_docs: local '%s' query='%s' → %d chars",
                         local_id, query[:50], len(result))
            return [{"type": "text", "value": result}]

    # --- Step 2: Fallback to Context7 API ---
    # Use original library_id for Context7 (it expects /org/project format)
    ctx7_id = library_id if library_id.startswith("/") else f"/{library_id}"
    params = urllib.parse.urlencode({"query": query, "libraryId": ctx7_id})
    url = f"{_CONTEXT7_API}/context?{params}"

    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": "CopilotCLI/0.1",
            "X-Context7-Source": "copilot-cli",
        })
        with urllib.request.urlopen(req, timeout=_TIMEOUT) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        logger.debug("Context7 API unreachable: %s", e)
        if local_id in LIBRARIES:
            return [{"type": "text", "value": (
                f"No matching sections found for query '{query}' in {local_id} docs. "
                "Try a different query with more specific terms."
            )}]
        return [{"type": "text", "value": (
            f"No bundled docs for '{library_id}' and Context7 API is unreachable.\n"
            f"Bundled libraries: playwright, selenium, cucumber, gherkin, java."
        )}]

    if not body or not body.strip():
        return [{"type": "text", "value": (
            f"No documentation found for '{library_id}'. "
            "The library ID may be invalid — call resolve_library_id to verify."
        )}]

    if len(body) > TOOL_OUTPUT_LIMIT:
        body = body[:TOOL_OUTPUT_LIMIT] + "\n\n... (truncated — narrow your query)"

    logger.debug("get_library_docs: Context7 '%s' query='%s' → %d chars",
                 library_id, query[:50], len(body))
    return [{"type": "text", "value": body}]
