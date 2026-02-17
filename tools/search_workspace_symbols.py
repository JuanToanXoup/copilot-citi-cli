"""Search for symbol definitions (functions, classes, variables) in the workspace."""

import subprocess
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


def execute(tool_input: dict, ctx: ToolContext) -> list:
    symbol = tool_input.get("symbolName", "")
    # Build a grep pattern: lines containing both a definition keyword and the symbol name
    # Use grep -E with alternation for definition keywords, then filter for symbol name
    def_pattern = "|".join(_DEF_PATTERNS)
    cmd = [
        "grep", "-rn", "-E",
        f"({def_pattern}).*{symbol}|{symbol}.*({def_pattern})",
        ctx.workspace_root,
        "--include=*.py", "--include=*.js", "--include=*.ts", "--include=*.tsx",
        "--include=*.java", "--include=*.go", "--include=*.rs", "--include=*.c",
        "--include=*.cpp", "--include=*.h", "--include=*.cs", "--include=*.rb",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    output = result.stdout[:6000]
    count = output.count("\n")
    logger.debug("search_workspace_symbols '%s': %d matches", symbol, count)
    return [{"type": "text", "value": output if output else f"No symbol definitions found for '{symbol}'."}]
