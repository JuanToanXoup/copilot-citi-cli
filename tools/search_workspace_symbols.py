"""Search for symbol definitions (functions, classes, variables) in the workspace."""

import os
import re
import subprocess

from platform_utils import find_grep
from tools._base import ToolContext
from log import get_logger

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


def execute(tool_input: dict, ctx: ToolContext) -> list:
    symbol = tool_input.get("symbolName", "")
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
