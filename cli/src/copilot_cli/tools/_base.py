"""Shared context, constants, and type aliases for tool modules.

Tool response format contract
-----------------------------
Every tool's ``execute()`` returns ``[{"type": "text", "value": "..."}]``.

The client registers **all** tools with the server via
``conversation/registerTools`` and automatically wraps results into the
tuple format the server expects::

    [{"content": [{"value": "..."}], "status": "success"}, None]

via ``CopilotClient._wrap_registered_tool_result()``.

Tools should NOT wrap their own result in the tuple format — the client
handles that automatically.
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
