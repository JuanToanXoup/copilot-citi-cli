"""SpecKit infrastructure tools exposed via MCP.

Each tool wraps a shell script or file operation that the speckit agent
prompts currently invoke inline.  By exposing them as MCP tools, any
MCP-compatible AI client can drive the spec-driven development workflow.
"""

import json
import os
import subprocess
from pathlib import Path

from speckit_mcp.server import MCPServer

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _repo_root() -> Path:
    """Locate the repository root (walk up from CWD looking for .specify/)."""
    cwd = Path.cwd()
    for parent in [cwd, *cwd.parents]:
        if (parent / ".specify").is_dir() or (parent / ".git").is_dir():
            return parent
    return cwd


def _run_script(name: str, args: list[str] | None = None, cwd: Path | None = None) -> str:
    """Run a .specify/scripts/bash/ script and return its stdout."""
    root = cwd or _repo_root()
    script = root / ".specify" / "scripts" / "bash" / name
    if not script.exists():
        return json.dumps({"error": f"Script not found: {script}"})
    cmd = ["bash", str(script)] + (args or [])
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(root))
    if result.returncode != 0:
        return json.dumps({"error": result.stderr.strip() or f"Script exited with code {result.returncode}"})
    return result.stdout.strip()


# ---------------------------------------------------------------------------
# Tool registration
# ---------------------------------------------------------------------------

def register_tools(server: MCPServer):
    """Register all speckit tools on the given MCP server."""

    # -- speckit_discover ----------------------------------------------------
    @server.tool(
        name="speckit_discover",
        description=(
            "Discover current feature context: feature directory, branch, "
            "available artifacts (spec.md, plan.md, tasks.md, etc.).  "
            "Wraps check-prerequisites.sh."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "require_tasks": {
                    "type": "boolean",
                    "description": "Require tasks.md to exist (implementation phase)",
                    "default": False,
                },
                "include_tasks": {
                    "type": "boolean",
                    "description": "Include tasks.md in available docs list",
                    "default": False,
                },
                "paths_only": {
                    "type": "boolean",
                    "description": "Return paths without validation",
                    "default": False,
                },
            },
            "required": [],
        },
    )
    def speckit_discover(args: dict) -> str:
        flags = ["--json"]
        if args.get("require_tasks"):
            flags.append("--require-tasks")
        if args.get("include_tasks"):
            flags.append("--include-tasks")
        if args.get("paths_only"):
            flags.append("--paths-only")
        return _run_script("check-prerequisites.sh", flags)

    # -- speckit_create_feature ----------------------------------------------
    @server.tool(
        name="speckit_create_feature",
        description=(
            "Create a new feature branch and spec scaffold.  "
            "Wraps create-new-feature.sh.  Returns branch name, spec file path, "
            "and feature number."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "description": {
                    "type": "string",
                    "description": "Natural-language feature description",
                },
                "short_name": {
                    "type": "string",
                    "description": "Optional 2-4 word slug for the branch (auto-derived if omitted)",
                },
                "number": {
                    "type": "integer",
                    "description": "Optional branch number (auto-incremented if omitted)",
                },
            },
            "required": ["description"],
        },
    )
    def speckit_create_feature(args: dict) -> str:
        flags = ["--json"]
        if args.get("short_name"):
            flags.extend(["--short-name", args["short_name"]])
        if args.get("number") is not None:
            flags.extend(["--number", str(args["number"])])
        flags.append(args["description"])
        return _run_script("create-new-feature.sh", flags)

    # -- speckit_setup_plan --------------------------------------------------
    @server.tool(
        name="speckit_setup_plan",
        description=(
            "Initialize the implementation plan from the plan template.  "
            "Wraps setup-plan.sh.  Must be on a feature branch."
        ),
        input_schema={
            "type": "object",
            "properties": {},
            "required": [],
        },
    )
    def speckit_setup_plan(args: dict) -> str:
        return _run_script("setup-plan.sh", ["--json"])

    # -- speckit_update_context ----------------------------------------------
    @server.tool(
        name="speckit_update_context",
        description=(
            "Update agent context files after plan changes.  "
            "Wraps update-agent-context.sh."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "agent": {
                    "type": "string",
                    "description": "Agent identifier (e.g. 'copilot')",
                    "default": "copilot",
                },
            },
            "required": [],
        },
    )
    def speckit_update_context(args: dict) -> str:
        agent = args.get("agent", "copilot")
        return _run_script("update-agent-context.sh", [agent])

    # -- speckit_load_template -----------------------------------------------
    @server.tool(
        name="speckit_load_template",
        description=(
            "Load a SpecKit template file (spec-template.md, plan-template.md, "
            "tasks-template.md, checklist-template.md, constitution-template.md)."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "template": {
                    "type": "string",
                    "description": "Template filename (e.g. 'spec-template.md')",
                    "enum": [
                        "spec-template.md",
                        "plan-template.md",
                        "tasks-template.md",
                        "checklist-template.md",
                        "constitution-template.md",
                        "agent-file-template.md",
                    ],
                },
            },
            "required": ["template"],
        },
    )
    def speckit_load_template(args: dict) -> str:
        root = _repo_root()
        path = root / ".specify" / "templates" / args["template"]
        if not path.exists():
            return json.dumps({"error": f"Template not found: {path}"})
        return path.read_text(encoding="utf-8")

    # -- speckit_load_artifact -----------------------------------------------
    @server.tool(
        name="speckit_load_artifact",
        description=(
            "Read a feature artifact (spec.md, plan.md, tasks.md, research.md, "
            "data-model.md, quickstart.md, or constitution.md)."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "artifact": {
                    "type": "string",
                    "description": "Artifact filename (e.g. 'spec.md', 'plan.md', 'constitution.md')",
                },
                "feature_dir": {
                    "type": "string",
                    "description": "Absolute path to the feature directory (from speckit_discover). "
                                   "Omit for constitution.md which lives in .specify/memory/.",
                },
            },
            "required": ["artifact"],
        },
    )
    def speckit_load_artifact(args: dict) -> str:
        artifact = args["artifact"]
        root = _repo_root()

        if artifact == "constitution.md":
            path = root / ".specify" / "memory" / "constitution.md"
        else:
            feature_dir = args.get("feature_dir")
            if not feature_dir:
                return json.dumps({"error": "feature_dir required for non-constitution artifacts"})
            path = Path(feature_dir) / artifact

        if not path.exists():
            return json.dumps({"error": f"Artifact not found: {path}"})
        return path.read_text(encoding="utf-8")

    # -- speckit_write_artifact ----------------------------------------------
    @server.tool(
        name="speckit_write_artifact",
        description=(
            "Write content to a feature artifact (spec.md, plan.md, tasks.md, "
            "research.md, data-model.md, quickstart.md, or constitution.md).  "
            "Creates parent directories as needed."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "artifact": {
                    "type": "string",
                    "description": "Artifact filename (e.g. 'spec.md')",
                },
                "content": {
                    "type": "string",
                    "description": "Full content to write",
                },
                "feature_dir": {
                    "type": "string",
                    "description": "Absolute path to the feature directory. "
                                   "Omit for constitution.md.",
                },
            },
            "required": ["artifact", "content"],
        },
    )
    def speckit_write_artifact(args: dict) -> str:
        artifact = args["artifact"]
        content = args["content"]
        root = _repo_root()

        if artifact == "constitution.md":
            path = root / ".specify" / "memory" / "constitution.md"
        else:
            feature_dir = args.get("feature_dir")
            if not feature_dir:
                return json.dumps({"error": "feature_dir required for non-constitution artifacts"})
            path = Path(feature_dir) / artifact

        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return json.dumps({"written": str(path), "bytes": len(content.encode("utf-8"))})

    # -- speckit_list_features -----------------------------------------------
    @server.tool(
        name="speckit_list_features",
        description=(
            "List all existing features in the specs/ directory with their "
            "available artifacts."
        ),
        input_schema={
            "type": "object",
            "properties": {},
            "required": [],
        },
    )
    def speckit_list_features(args: dict) -> str:
        root = _repo_root()
        specs_dir = root / "specs"
        if not specs_dir.is_dir():
            return json.dumps({"features": []})

        features = []
        for d in sorted(specs_dir.iterdir()):
            if not d.is_dir():
                continue
            artifacts = [f.name for f in d.iterdir() if f.is_file()]
            has_contracts = (d / "contracts").is_dir() and any((d / "contracts").iterdir())
            has_checklists = (d / "checklists").is_dir() and any((d / "checklists").iterdir())
            entry = {
                "name": d.name,
                "path": str(d),
                "artifacts": sorted(artifacts),
            }
            if has_contracts:
                entry["artifacts"].append("contracts/")
            if has_checklists:
                checklist_files = [f.name for f in (d / "checklists").iterdir() if f.is_file()]
                entry["checklists"] = sorted(checklist_files)
            features.append(entry)

        return json.dumps({"features": features}, indent=2)

    # -- speckit_status ------------------------------------------------------
    @server.tool(
        name="speckit_status",
        description=(
            "Show pipeline progress for the current feature: which artifacts "
            "exist, task completion counts, and checklist pass/fail."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "feature_dir": {
                    "type": "string",
                    "description": "Absolute path to the feature directory (from speckit_discover)",
                },
            },
            "required": ["feature_dir"],
        },
    )
    def speckit_status(args: dict) -> str:
        fd = Path(args["feature_dir"])
        if not fd.is_dir():
            return json.dumps({"error": f"Feature directory not found: {fd}"})

        status = {"feature": fd.name, "path": str(fd), "pipeline": {}}

        # Check each pipeline stage artifact
        stages = [
            ("specify", "spec.md"),
            ("plan", "plan.md"),
            ("tasks", "tasks.md"),
            ("research", "research.md"),
            ("data_model", "data-model.md"),
            ("quickstart", "quickstart.md"),
        ]
        for stage, filename in stages:
            path = fd / filename
            status["pipeline"][stage] = path.exists()

        status["pipeline"]["contracts"] = (
            (fd / "contracts").is_dir()
            and any((fd / "contracts").iterdir())
        )

        # Task completion
        tasks_path = fd / "tasks.md"
        if tasks_path.exists():
            text = tasks_path.read_text(encoding="utf-8")
            total = text.count("- [ ]") + text.count("- [x]") + text.count("- [X]")
            done = text.count("- [x]") + text.count("- [X]")
            status["tasks"] = {"total": total, "completed": done}

        # Checklist status
        checklists_dir = fd / "checklists"
        if checklists_dir.is_dir():
            cl_status = {}
            for cl_file in sorted(checklists_dir.iterdir()):
                if not cl_file.is_file():
                    continue
                text = cl_file.read_text(encoding="utf-8")
                total = text.count("- [ ]") + text.count("- [x]") + text.count("- [X]")
                done = text.count("- [x]") + text.count("- [X]")
                cl_status[cl_file.name] = {"total": total, "completed": done, "pass": total == done}
            status["checklists"] = cl_status

        return json.dumps(status, indent=2)
