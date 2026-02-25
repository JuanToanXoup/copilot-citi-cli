"""MCP resources: expose speckit agent prompts and templates as readable resources.

Clients can list available prompts via resources/list and fetch them via
resources/read.  This lets any MCP-compatible AI client discover the full
speckit prompt library without needing the GitHub Copilot agent framework.
"""

import os
from pathlib import Path

from speckit_mcp.server import MCPServer


def _repo_root() -> Path:
    cwd = Path.cwd()
    for parent in [cwd, *cwd.parents]:
        if (parent / ".specify").is_dir() or (parent / ".git").is_dir():
            return parent
    return cwd


def register_resources(server: MCPServer):
    """Register speckit prompt and template resources."""

    # -- Static resource: list of available prompts --------------------------
    @server.resource(
        uri="speckit://prompts",
        name="SpecKit Prompt Index",
        description="List all available speckit agent prompts",
    )
    def prompt_index(uri: str) -> str:
        root = _repo_root()
        agents_dir = root / ".github" / "agents"
        if not agents_dir.is_dir():
            return "No agent prompts found."
        names = sorted(f.stem.replace(".agent", "") for f in agents_dir.glob("speckit.*.agent.md"))
        lines = ["# SpecKit Agent Prompts\n"]
        for name in names:
            lines.append(f"- `speckit://prompt/{name}` — {name}")
        return "\n".join(lines)

    # -- Resource template: individual agent prompts -------------------------
    @server.resource_template(
        uri_template="speckit://prompt/{name}",
        name="SpecKit Agent Prompt",
        description="Fetch a speckit agent prompt by name (e.g. speckit.specify, speckit.plan)",
        mime_type="text/markdown",
    )
    def prompt_content(uri: str, params: dict) -> str:
        name = params.get("name", "")
        root = _repo_root()
        # Try .github/agents/ first (full agent definition)
        agent_file = root / ".github" / "agents" / f"{name}.agent.md"
        if agent_file.exists():
            return agent_file.read_text(encoding="utf-8")
        # Fall back to .github/prompts/
        prompt_file = root / ".github" / "prompts" / f"{name}.prompt.md"
        if prompt_file.exists():
            return prompt_file.read_text(encoding="utf-8")
        return f"Prompt not found: {name}"

    # -- Static resource: constitution ---------------------------------------
    @server.resource(
        uri="speckit://constitution",
        name="Project Constitution",
        description="The project's governing constitution (.specify/memory/constitution.md)",
    )
    def constitution(uri: str) -> str:
        root = _repo_root()
        path = root / ".specify" / "memory" / "constitution.md"
        if path.exists():
            return path.read_text(encoding="utf-8")
        return "Constitution not yet created. Run speckit.constitution to create one."

    # -- Resource template: templates ----------------------------------------
    @server.resource_template(
        uri_template="speckit://template/{name}",
        name="SpecKit Template",
        description="Fetch a speckit template by name (e.g. spec-template, plan-template)",
        mime_type="text/markdown",
    )
    def template_content(uri: str, params: dict) -> str:
        name = params.get("name", "")
        root = _repo_root()
        path = root / ".specify" / "templates" / f"{name}.md"
        if path.exists():
            return path.read_text(encoding="utf-8")
        return f"Template not found: {name}"
