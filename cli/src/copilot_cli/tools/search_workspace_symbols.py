"""Search for symbol definitions (functions, classes, variables) in the workspace."""

import os
import re
import subprocess

from copilot_cli.platform_utils import find_grep
from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "search_workspace_symbols",
    "description": "Search for symbol definitions (functions, classes, methods, variables) in the workspace by name.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "symbolName": {"type": "string", "description": "The symbol name to search for."},
        },
        "required": ["symbolName"],
    },
}

# Patterns that indicate symbol definitions across common languages
_DEF_PATTERNS = [
    r"\bdef\s",        # Python function
    r"\bclass\s",      # Python/Java/JS/TS class
    r"\bfunction\s",   # JS/TS function
    r"\bconst\s",      # JS/TS const
    r"\blet\s",        # JS/TS let
    r"\bvar\s",        # JS/Go var
    r"\bfunc\s",       # Go function
    r"\bfn\s",         # Rust function
    r"\btype\s",       # Go/TS type
    r"\binterface\s",  # TS/Java interface
    r"\benum\s",       # enum definitions
    r"\bstruct\s",     # Go/Rust/C struct
]

_CODE_EXTENSIONS = {
    ".py", ".js", ".ts", ".tsx", ".java", ".go", ".rs",
    ".c", ".cpp", ".h", ".cs", ".rb",
}

_INCLUDE_FLAGS = [f"--include=*{ext}" for ext in _CODE_EXTENSIONS]


def _python_symbol_search(symbol: str, root: str) -> str:
    """Pure-Python fallback when grep is not available."""
    def_pattern = "|".join(_DEF_PATTERNS)
    combined = re.compile(f"({def_pattern}).*{re.escape(symbol)}|{re.escape(symbol)}.*({def_pattern})")
    lines = []
    for dirpath, _, filenames in os.walk(root):
        for fname in filenames:
            ext = os.path.splitext(fname)[1].lower()
            if ext not in _CODE_EXTENSIONS:
                continue
            fpath = os.path.join(dirpath, fname)
            try:
                with open(fpath, "r", errors="replace") as f:
                    for lineno, line in enumerate(f, 1):
                        if combined.search(line):
                            lines.append(f"{fpath}:{lineno}:{line.rstrip()}")
                            if len("\n".join(lines)) > 6000:
                                return "\n".join(lines)
            except (OSError, UnicodeDecodeError):
                continue
    return "\n".join(lines)


def _lsp_symbol_search(symbol: str, ctx: ToolContext) -> str | None:
    """Try workspace/symbol via LSP. Returns formatted output or None."""
    from copilot_cli.lsp_bridge import _SYMBOL_KINDS, _uri_to_path
    bridge = ctx.lsp_bridge
    if not bridge:
        return None

    # Try each workspace language until one returns results
    results = []
    for lang in bridge.get_workspace_languages():
        server = bridge.get_server(lang)
        if not server:
            continue
        symbols = server.workspace_symbol(symbol)
        for sym in symbols:
            name = sym.get("name", "")
            kind_num = sym.get("kind", 0)
            kind = _SYMBOL_KINDS.get(kind_num, "Symbol")
            loc = sym.get("location", {})
            uri = loc.get("uri", "")
            rng = loc.get("range", {}).get("start", {})
            line = rng.get("line", 0) + 1  # 0-indexed -> 1-indexed
            path = _uri_to_path(uri)
            container = sym.get("containerName", "")
            container_str = f"  ({container})" if container else ""
            results.append(f"{path}:{line}: [{kind}] {name}{container_str}")
        if results:
            break  # Got results from this language
    if not results:
        return None
    return "\n".join(results[:100])  # Cap at 100 results


def execute(tool_input: dict, ctx: ToolContext) -> list:
    symbol = tool_input.get("symbolName", "")

    # Try LSP first
    lsp_output = _lsp_symbol_search(symbol, ctx)
    if lsp_output:
        count = lsp_output.count("\n") + 1
        logger.debug("search_workspace_symbols '%s': %d matches (LSP)", symbol, count)
        return [{"type": "text", "value": lsp_output}]

    # Fallback: grep-based search
    def_pattern = "|".join(_DEF_PATTERNS)

    grep_bin = find_grep()
    if grep_bin:
        cmd = [
            grep_bin, "-rn", "-E",
            f"({def_pattern}).*{symbol}|{symbol}.*({def_pattern})",
            ctx.workspace_root,
        ] + _INCLUDE_FLAGS
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        output = result.stdout[:6000]
    else:
        output = _python_symbol_search(symbol, ctx.workspace_root)

    count = output.count("\n")
    logger.debug("search_workspace_symbols '%s': %d matches", symbol, count)
    return [{"type": "text", "value": output if output else f"No symbol definitions found for '{symbol}'."}]
