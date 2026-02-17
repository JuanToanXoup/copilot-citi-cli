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
