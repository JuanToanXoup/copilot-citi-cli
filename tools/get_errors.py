"""Check files for syntax/compile errors."""

import os
import subprocess
from tools._base import ToolContext
from log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "get_errors",
    "description": "Check files for syntax/compile errors.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "filePaths": {
                "type": "array",
                "items": {"type": "string"},
                "description": "File paths to check. Omit for all files.",
            },
        },
        "required": [],
    },
}

# LSP DiagnosticSeverity enum
_SEVERITY = {1: "Error", 2: "Warning", 3: "Info", 4: "Hint"}


def _lsp_get_errors(file_paths: list[str], ctx: ToolContext) -> str | None:
    """Try LSP diagnostics. Returns formatted errors or None."""
    bridge = ctx.lsp_bridge
    if not bridge:
        return None

    all_errors = []
    for fp in file_paths:
        server = bridge.get_server_for_file(fp)
        if not server:
            continue
        if not os.path.isfile(fp):
            continue
        try:
            with open(fp, "r", errors="replace") as f:
                text = f.read()
        except OSError:
            continue

        diagnostics = server.get_diagnostics(fp, text)
        for diag in diagnostics:
            severity = _SEVERITY.get(diag.get("severity", 1), "Error")
            msg = diag.get("message", "")
            rng = diag.get("range", {}).get("start", {})
            line = rng.get("line", 0) + 1
            col = rng.get("character", 0) + 1
            source = diag.get("source", "")
            source_str = f" [{source}]" if source else ""
            all_errors.append(f"{fp}:{line}:{col}: {severity}: {msg}{source_str}")

    if not all_errors:
        return None  # No LSP diagnostics — fall through to legacy check
    return "\n".join(all_errors)


def execute(tool_input: dict, ctx: ToolContext) -> list:
    file_paths = tool_input.get("filePaths", [])
    targets = file_paths if file_paths else [ctx.workspace_root]

    # Expand directory targets to individual files for LSP
    expanded = []
    for fp in targets:
        if os.path.isdir(fp):
            for root, _, files in os.walk(fp):
                for fname in files:
                    expanded.append(os.path.join(root, fname))
        else:
            expanded.append(fp)

    # Try LSP first
    lsp_output = _lsp_get_errors(expanded, ctx)
    if lsp_output:
        count = lsp_output.count("\n") + 1
        logger.debug("get_errors: %d issues (LSP)", count)
        return [{"type": "text", "value": lsp_output}]

    # Fallback: py_compile for Python files
    errors = []
    for fp in expanded:
        if fp.endswith(".py"):
            result = subprocess.run(
                ["python3", "-m", "py_compile", fp],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode != 0:
                errors.append(result.stderr.strip())
    result_text = "\n".join(errors) if errors else "No errors found."
    logger.debug("get_errors: %d issues", len(errors))
    return [{"type": "text", "value": result_text}]
