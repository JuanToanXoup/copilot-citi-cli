"""Soft-validation for worker Q&A schemas.

Worker question and answer schemas are *descriptive, not prescriptive* — they
guide the orchestrator and worker LLMs but never hard-reject mismatches.

Schema format (JSON Schema subset)::

    {
        "file_path": {"type": "string", "description": "Path to review", "required": true},
        "diff":      {"type": "string", "description": "The diff to review"},
        "goal":      {"type": "string", "description": "What to focus on"},
    }

Soft-validation means:
- Extract matching fields from the data, coerce types best-effort
- Extra fields in the data are preserved (never stripped)
- Missing required fields produce a warning, not an error
- The raw input is always available as a fallback
"""

from __future__ import annotations

import json
from typing import Any


# ── Schema helpers ───────────────────────────────────────────────────────────

def schema_to_json_schema(schema: dict) -> dict:
    """Convert our compact schema format to standard JSON Schema.

    Input::

        {"file_path": {"type": "string", "required": true, "description": "..."}}

    Output::

        {
            "type": "object",
            "properties": {
                "file_path": {"type": "string", "description": "..."}
            },
            "required": ["file_path"]
        }
    """
    properties = {}
    required = []

    for field_name, field_def in schema.items():
        if not isinstance(field_def, dict):
            continue
        prop = {}
        if "type" in field_def:
            prop["type"] = field_def["type"]
        if "description" in field_def:
            prop["description"] = field_def["description"]
        if "items" in field_def:
            prop["items"] = field_def["items"]
        if "default" in field_def:
            prop["default"] = field_def["default"]
        properties[field_name] = prop

        if field_def.get("required", False):
            required.append(field_name)

    result = {"type": "object", "properties": properties}
    if required:
        result["required"] = required
    return result


def schema_to_description(schema: dict, label: str = "Parameters") -> str:
    """Render a schema as a human-readable description for LLM prompts.

    Returns a string like::

        Parameters:
        - file_path (string, required): Path to the file to review
        - diff (string): The diff to review
        - goal (string): What to focus on
    """
    if not schema:
        return ""

    lines = [f"{label}:"]
    for field_name, field_def in schema.items():
        if not isinstance(field_def, dict):
            continue
        type_str = field_def.get("type", "any")
        req = ", required" if field_def.get("required", False) else ""
        desc = field_def.get("description", "")
        desc_part = f": {desc}" if desc else ""
        lines.append(f"  - {field_name} ({type_str}{req}){desc_part}")

    return "\n".join(lines)


# ── Soft validation ──────────────────────────────────────────────────────────

def _coerce_number(value: Any) -> float | None:
    """Try to coerce a value to a number."""
    if isinstance(value, (int, float)):
        return value
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def _coerce_integer(value: Any) -> int | None:
    """Try to coerce a value to an integer."""
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    if isinstance(value, float) and value == int(value):
        return int(value)
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return None
    return None


def _coerce_boolean(value: Any) -> bool | None:
    """Try to coerce a value to a boolean."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        if value.lower() in ("true", "1", "yes"):
            return True
        if value.lower() in ("false", "0", "no"):
            return False
    if isinstance(value, (int, float)):
        return bool(value)
    return None


def _coerce_value(value: Any, expected_type: str) -> Any:
    """Best-effort type coercion. Returns the original value if coercion fails."""
    if expected_type == "string":
        return str(value) if value is not None else value
    elif expected_type == "number":
        result = _coerce_number(value)
        return result if result is not None else value
    elif expected_type == "integer":
        result = _coerce_integer(value)
        return result if result is not None else value
    elif expected_type == "boolean":
        result = _coerce_boolean(value)
        return result if result is not None else value
    # array, object, or unknown — return as-is
    return value


def soft_validate(data: dict | str, schema: dict) -> dict:
    """Soft-validate data against a schema.

    Returns a dict with:
    - ``parsed``: dict of fields that matched the schema (with coercion)
    - ``extras``: dict of fields present in data but not in schema
    - ``missing``: list of required field names that were absent
    - ``warnings``: list of human-readable warning strings
    - ``raw``: the original data, unchanged

    This function **never raises** — all mismatches are reported as warnings.
    """
    warnings: list[str] = []

    # Handle string input (e.g. raw LLM reply)
    if isinstance(data, str):
        # Try to parse as JSON
        try:
            parsed_data = json.loads(data)
            if not isinstance(parsed_data, dict):
                return {
                    "parsed": {},
                    "extras": {},
                    "missing": [
                        f for f, d in schema.items()
                        if isinstance(d, dict) and d.get("required", False)
                    ],
                    "warnings": ["Response is not a JSON object; treating as raw reply"],
                    "raw": data,
                }
            data = parsed_data
        except (json.JSONDecodeError, TypeError):
            return {
                "parsed": {},
                "extras": {},
                "missing": [
                    f for f, d in schema.items()
                    if isinstance(d, dict) and d.get("required", False)
                ],
                "warnings": ["Response is not valid JSON; treating as raw reply"],
                "raw": data,
            }

    parsed = {}
    extras = {}
    missing = []

    schema_fields = {
        k for k, v in schema.items() if isinstance(v, dict)
    }

    for field_name, field_def in schema.items():
        if not isinstance(field_def, dict):
            continue

        if field_name in data:
            value = data[field_name]
            expected_type = field_def.get("type")
            if expected_type:
                coerced = _coerce_value(value, expected_type)
                if coerced is not value and coerced != value:
                    warnings.append(
                        f"Field '{field_name}': coerced {type(value).__name__} "
                        f"to {expected_type}"
                    )
                parsed[field_name] = coerced
            else:
                parsed[field_name] = value
        elif field_def.get("required", False):
            missing.append(field_name)
            warnings.append(f"Required field '{field_name}' is missing")

    # Collect extra fields (not in schema)
    for key, value in data.items():
        if key not in schema_fields:
            extras[key] = value

    return {
        "parsed": parsed,
        "extras": extras,
        "missing": missing,
        "warnings": warnings,
        "raw": data,
    }


def build_answer_from_validation(validation_result: dict) -> dict:
    """Build a unified answer dict from a soft-validation result.

    Merges parsed fields and extras into a single dict, with a
    ``_validation`` key containing metadata.
    """
    answer = {}
    answer.update(validation_result["parsed"])
    answer.update(validation_result["extras"])
    answer["_validation"] = {
        "missing": validation_result["missing"],
        "warnings": validation_result["warnings"],
    }
    return answer
