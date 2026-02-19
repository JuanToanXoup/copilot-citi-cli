"""Preset agent templates for the Agent Builder.

Templates are loaded from individual TOML files in the templates/ directory.
Each file defines one template (name, description, system_prompt, tools, etc.).
MCP and LSP server configs can be defined per-template or inherited from
the main config.toml at runtime.
"""

import os

try:
    import tomllib  # Python 3.11+
except ModuleNotFoundError:
    import tomli as tomllib  # fallback

_TEMPLATES_DIR = os.path.join(os.path.dirname(__file__), "templates")


def _load_templates() -> dict:
    """Load all .toml files from the templates/ directory.

    Each template dict gets a ``_source_path`` key so the Agent Builder
    can save changes back to the originating file.
    """
    templates = {}
    if not os.path.isdir(_TEMPLATES_DIR):
        return templates
    for fname in sorted(os.listdir(_TEMPLATES_DIR)):
        if not fname.endswith(".toml"):
            continue
        template_id = fname[:-5]  # strip .toml
        path = os.path.join(_TEMPLATES_DIR, fname)
        with open(path, "rb") as f:
            data = tomllib.load(f)
        # Strip leading/trailing whitespace from multiline system_prompt
        if "system_prompt" in data:
            data["system_prompt"] = data["system_prompt"].strip()
        data["_source_path"] = path
        templates[template_id] = data
    return templates


TEMPLATES = _load_templates()


def reload_templates():
    """Re-read template files from disk (call after saving to a template)."""
    global TEMPLATES
    TEMPLATES = _load_templates()


def get_template(name: str) -> dict | None:
    """Return a deep copy of the named template, or None."""
    import copy
    t = TEMPLATES.get(name)
    return copy.deepcopy(t) if t else None


def list_templates() -> list[dict]:
    """Return summary list of all templates."""
    return [
        {"id": k, "name": v["name"], "description": v["description"]}
        for k, v in TEMPLATES.items()
    ]
