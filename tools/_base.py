"""Shared context, constants, and type aliases for tool modules.

Tool response format contract
-----------------------------
Each tool's ``execute()`` returns one of two shapes:

1. **dict** — ``{"result": "success"|"error", ...}``
   Used by *built-in* tools whose names are in ``BUILTIN_TOOL_NAMES``.
   The server expects this dict as-is (sent directly via ``_send_response``).

2. **list** — ``[{"type": "text", "value": "..."}]``
   Used by *registered* (non-built-in) tools. The client automatically wraps
   this into ``[{"content": [...], "status": "success"}, None]`` via
   ``CopilotClient._wrap_registered_tool_result()`` before sending to the
   server.

Tools should NOT wrap their own result in the tuple format — the client
handles that automatically for all registered tools.
"""

import dataclasses
from typing import Callable, Union

# Maximum characters of tool output returned to the model.
TOOL_OUTPUT_LIMIT = 4000

# Return type for tool execute() functions.
# dict → built-in tools (response sent as-is to server)
# list → registered tools (auto-wrapped by _wrap_registered_tool_result)
ToolResult = Union[dict, list]


@dataclasses.dataclass
class ToolContext:
    workspace_root: str
    sync_file_to_server: Callable[[str, str], None]  # (file_path, content) -> None
    open_document: Callable[[str, str, str], None]    # (uri, lang, text) -> None
    lsp_bridge: object = None  # LSPBridgeManager instance (None = no LSP available)
