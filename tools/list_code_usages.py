"""Find all usages/references of a symbol in the workspace."""

import os
import subprocess
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "list_code_usages",
    "description": "Find all usages/references of a symbol name in the workspace or specific files.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "symbolName": {"type": "string", "description": "The symbol name to search for."},
            "filePaths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "Optional list of file paths to restrict the search to.",
            },
        },
        "required": ["symbolName"],
    },
}


def _lsp_find_references(symbol: str, file_paths: list[str],
                         ctx: ToolContext) -> str | None:
    """Try LSP references. Returns formatted output or None."""
    from lsp_bridge import _uri_to_path
    bridge = ctx.lsp_bridge
    if not bridge:
        return None

    # We need a file to anchor the search — use first provided file or
    # try to find the symbol definition in the workspace
    anchor_file = None
    if file_paths:
        anchor_file = file_paths[0]
    else:
        # Search workspace files for the symbol to get an anchor
        for root, _, files in os.walk(ctx.workspace_root):
            parts = root.split(os.sep)
            if any(p.startswith(".") or p in ("node_modules", "__pycache__",
                                               "venv", ".venv") for p in parts):
                continue
            for fname in files:
                fpath = os.path.join(root, fname)
                if bridge.get_server_for_file(fpath):
                    anchor_file = fpath
                    break
            if anchor_file:
                break
    if not anchor_file:
        return None

    # Resolve symbol position
    pos = bridge.find_symbol_position(symbol, anchor_file)
    if not pos:
        return None

    resolved_file, line, col = pos
    server = bridge.get_server_for_file(resolved_file)
    if not server:
        return None

    # Read file content for LSP
    try:
        with open(resolved_file, "r", errors="replace") as f:
            text = f.read()
    except OSError:
        return None

    refs = server.find_references(resolved_file, line, col, text)
    if not refs:
        return None

    results = []
    for ref in refs:
        uri = ref.get("uri", "")
        rng = ref.get("range", {}).get("start", {})
        ref_line = rng.get("line", 0) + 1
        ref_col = rng.get("character", 0) + 1
        path = _uri_to_path(uri)
        # Read the actual line for context
        line_text = ""
        try:
            with open(path, "r", errors="replace") as f:
                for i, l in enumerate(f):
                    if i == ref_line - 1:
                        line_text = l.rstrip()
                        break
        except OSError:
            pass
        results.append(f"{path}:{ref_line}:{ref_col}: {line_text}")

    if not results:
        return None
    return "\n".join(results[:100])


def execute(tool_input: dict, ctx: ToolContext) -> list:
    symbol = tool_input.get("symbolName", "")
    file_paths = tool_input.get("filePaths", [])

    # Try LSP first
    lsp_output = _lsp_find_references(symbol, file_paths, ctx)
    if lsp_output:
        count = lsp_output.count("\n") + 1
        logger.debug("list_code_usages '%s': %d matches (LSP)", symbol, count)
        return [{"type": "text", "value": lsp_output}]

    # Fallback: grep
    cmd = ["grep", "-rn", "-F", symbol]
    if file_paths:
        cmd = ["grep", "-n", "-F", symbol] + file_paths
    else:
        cmd.append(ctx.workspace_root)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    output = result.stdout[:6000]
    count = output.count("\n")
    logger.debug("list_code_usages '%s': %d matches", symbol, count)
    return [{"type": "text", "value": output if output else f"No usages found for '{symbol}'."}]
