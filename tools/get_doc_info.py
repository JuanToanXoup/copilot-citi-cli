"""Extract docstrings and leading comments from source files."""

import os
import re
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "get_doc_info",
    "description": "Extract documentation — docstrings, module-level comments, and function/class signatures — from source files.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePaths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "File paths to extract documentation from.",
            },
        },
        "required": ["filePaths"],
    },
}

# Regex patterns for extracting doc-like structures
_PYTHON_DOC = re.compile(
    r'^([ \t]*(?:def|class)\s+\w+[^\n]*\n)'   # function/class signature
    r'([ \t]*"""[\s\S]*?""")',                   # docstring
    re.MULTILINE,
)
_COMMENT_BLOCK = re.compile(
    r'((?:^[ \t]*(?://|#)[^\n]*\n){2,})',  # 2+ consecutive comment lines
    re.MULTILINE,
)

# Patterns to find definition lines (for LSP hover)
_DEF_LINE = re.compile(
    r'^[ \t]*(def|class|function|func|fn|const|let|var|type|interface|struct|enum)\s+(\w+)',
    re.MULTILINE,
)


def _extract_python_docs(content: str) -> str:
    """Extract Python docstrings with their signatures."""
    parts = []
    # Module docstring
    m = re.match(r'^("""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\')', content)
    if m:
        parts.append(f"Module docstring:\n{m.group(0)}")
    # Function/class docstrings
    for m in _PYTHON_DOC.finditer(content):
        parts.append(f"{m.group(1).strip()}\n{m.group(2).strip()}")
    return "\n\n".join(parts) if parts else ""


def _extract_comment_blocks(content: str) -> str:
    """Extract multi-line comment blocks."""
    parts = []
    for m in _COMMENT_BLOCK.finditer(content):
        parts.append(m.group(0).strip())
    return "\n\n".join(parts) if parts else ""


def _lsp_get_doc_info(fp: str, content: str, ctx: ToolContext) -> str | None:
    """Try LSP hover at each definition in the file. Returns docs or None."""
    bridge = ctx.lsp_bridge
    if not bridge:
        return None

    server = bridge.get_server_for_file(fp)
    if not server:
        return None

    lines = content.split("\n")
    parts = []
    for match in _DEF_LINE.finditer(content):
        keyword = match.group(1)
        name = match.group(2)
        # Find the line number of this match
        line_start = content[:match.start()].count("\n")
        col = match.start(2) - content.rfind("\n", 0, match.start(2)) - 1

        hover_text = server.hover(fp, line_start, col, content)
        if hover_text:
            parts.append(f"{keyword} {name} (line {line_start + 1}):\n{hover_text}")
        else:
            # Include the signature line even without hover info
            if line_start < len(lines):
                parts.append(f"{keyword} {name} (line {line_start + 1}):\n{lines[line_start].strip()}")

        if len(parts) >= 50:
            break

    if not parts:
        return None
    return "\n\n".join(parts)


def execute(tool_input: dict, ctx: ToolContext) -> list:
    file_paths = tool_input.get("filePaths", [])
    results = []
    for fp in file_paths:
        if not os.path.isfile(fp):
            results.append(f"## {fp}\nFile not found.")
            continue
        try:
            with open(fp, "r", errors="replace") as f:
                content = f.read(50000)
        except OSError as e:
            results.append(f"## {fp}\nError reading file: {e}")
            continue

        # Try LSP hover for rich type info
        lsp_docs = _lsp_get_doc_info(fp, content, ctx)
        if lsp_docs:
            results.append(f"## {fp}\n{lsp_docs}")
            continue

        # Fallback: regex-based extraction
        ext = os.path.splitext(fp)[1].lower()
        docs = ""
        if ext == ".py":
            docs = _extract_python_docs(content)
        if not docs:
            docs = _extract_comment_blocks(content)
        if not docs:
            # Fallback: first 20 lines as context
            lines = content.split("\n")[:20]
            docs = "\n".join(lines)

        results.append(f"## {fp}\n{docs}")

    output = "\n\n".join(results) if results else "No files provided."
    logger.debug("get_doc_info: %d files processed", len(file_paths))
    return [{"type": "text", "value": output}]
