"""Shared context, constants, and type aliases for tool modules.

Tool response format contract
-----------------------------
Each tool's ``execute()`` returns one of two shapes depending on whether it
is a *built-in* or *registered* tool:

1. **list** — ``[{"type": "text", "value": "..."}]``
   Used by **all built-in tools** (names in ``BUILTIN_TOOL_NAMES``).
   The server processes this array directly — each element must have
   ``type`` and ``value`` keys. This is also the format used by
   registered tools *before* wrapping.

2. Registered (non-built-in) tools also return the same ``list`` shape.
   The client automatically wraps it into the tuple format the server
   expects: ``[{"content": [{"value": "..."}], "status": "success"}, None]``
   via ``CopilotClient._wrap_registered_tool_result()``.

Tools should NOT wrap their own result in the tuple format — the client
handles that automatically for all registered tools.
"""

import dataclasses
from typing import Callable

# Maximum characters of tool output returned to the model.
TOOL_OUTPUT_LIMIT = 4000

# Return type for tool execute() functions.
ToolResult = list


@dataclasses.dataclass
class ToolContext:
    workspace_root: str
    sync_file_to_server: Callable[[str, str], None]  # (file_path, content) -> None
    open_document: Callable[[str, str, str], None]    # (uri, lang, text) -> None
    lsp_bridge: object = None  # LSPBridgeManager instance (None = no LSP available)
