"""Apply multiple string replacements across one or more files in a single operation."""

from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "multi_replace_string",
    "description": "Apply multiple string replacements across one or more files in a single operation.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "explanation": {"type": "string", "description": "A brief explanation of the multi-replace operation."},
            "replacements": {
                "type": "array",
                "description": "Array of replacement operations to apply sequentially.",
                "items": {
                    "type": "object",
                    "properties": {
                        "explanation": {"type": "string", "description": "Explanation of this replacement."},
                        "filePath": {"type": "string", "description": "Absolute path to the file."},
                        "oldString": {"type": "string", "description": "The exact text to find."},
                        "newString": {"type": "string", "description": "The replacement text."},
                    },
                    "required": ["explanation", "filePath", "oldString", "newString"],
                },
                "minItems": 1,
            },
        },
        "required": ["explanation", "replacements"],
    },
}


def execute(tool_input: dict, ctx: ToolContext) -> list:
    explanation = tool_input.get("explanation", "")
    replacements = tool_input.get("replacements", [])
    logger.debug("Multi-replace (%d ops): %s", len(replacements), explanation)
    for i, rep in enumerate(replacements):
        fp = rep.get("filePath", "")
        old_s = rep.get("oldString", "")
        new_s = rep.get("newString", "")
        with open(fp, "r") as f:
            content = f.read()
        if old_s not in content:
            return [{"type": "text", "value": f"Error: Replacement {i}: oldString not found in {fp}"}]
        content = content.replace(old_s, new_s, 1)
        with open(fp, "w") as f:
            f.write(content)
        ctx.sync_file_to_server(fp, content)
        logger.debug("  [%d/%d] Replaced in %s: %s", i+1, len(replacements), fp, rep.get('explanation', ''))
    return [{"type": "text", "value": f"Applied {len(replacements)} replacements"}]
