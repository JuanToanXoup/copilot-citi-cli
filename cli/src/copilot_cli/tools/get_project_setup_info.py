"""Return common project scaffolding/setup info based on project type."""

import os
from copilot_cli.tools._base import ToolContext
from copilot_cli.log import get_logger

logger = get_logger("tools")

SCHEMA = {
    "name": "get_project_setup_info",
    "description": "Return project setup information — detected frameworks, config files, entry points, and common commands.",
    "inputSchema": {
        "type": "object",
        "properties": {
            "projectType": {
                "type": "string",
                "description": "Hint for what kind of project: 'auto' (detect), 'python', 'node', 'java', 'go', 'rust', etc.",
            },
        },
        "required": ["projectType"],
    },
}

# Map of config file -> project info
_DETECTORS = [
    ("pyproject.toml",    "python",  "Python (pyproject.toml)"),
    ("setup.py",          "python",  "Python (setup.py)"),
    ("setup.cfg",         "python",  "Python (setup.cfg)"),
    ("requirements.txt",  "python",  "Python (requirements.txt)"),
    ("Pipfile",           "python",  "Python (Pipfile)"),
    ("package.json",      "node",    "Node.js (package.json)"),
    ("tsconfig.json",     "node",    "TypeScript (tsconfig.json)"),
    ("pom.xml",           "java",    "Java (Maven)"),
    ("build.gradle",      "java",    "Java (Gradle)"),
    ("build.gradle.kts",  "java",    "Kotlin (Gradle KTS)"),
    ("go.mod",            "go",      "Go (go.mod)"),
    ("Cargo.toml",        "rust",    "Rust (Cargo.toml)"),
    ("Gemfile",           "ruby",    "Ruby (Gemfile)"),
    ("Makefile",          "make",    "Makefile project"),
    ("CMakeLists.txt",    "cmake",   "C/C++ (CMake)"),
    ("docker-compose.yml","docker",  "Docker Compose"),
    ("Dockerfile",        "docker",  "Docker"),
]


def execute(tool_input: dict, ctx: ToolContext) -> list:
    project_type = tool_input.get("projectType", "auto")
    root = ctx.workspace_root
    info_lines = [f"Workspace: {root}\n"]

    # Detect project types
    detected = []
    for config_file, ptype, label in _DETECTORS:
        if os.path.exists(os.path.join(root, config_file)):
            detected.append((config_file, ptype, label))

    if detected:
        info_lines.append("## Detected project files")
        for config_file, ptype, label in detected:
            info_lines.append(f"  - {label}: {config_file}")
    else:
        info_lines.append("No standard project config files detected.")

    # Show key config file contents (first few lines)
    for config_file, ptype, label in detected:
        if project_type not in ("auto", ptype):
            continue
        fp = os.path.join(root, config_file)
        try:
            with open(fp, "r") as f:
                head = f.read(2000)
            info_lines.append(f"\n## {config_file}\n```\n{head}\n```")
        except OSError:
            pass

    # List top-level directory structure
    try:
        entries = sorted(os.listdir(root))[:50]
        info_lines.append("\n## Top-level files/dirs")
        for e in entries:
            full = os.path.join(root, e)
            tag = "[dir]" if os.path.isdir(full) else "[file]"
            info_lines.append(f"  {tag} {e}")
    except OSError:
        pass

    output = "\n".join(info_lines)
    logger.debug("get_project_setup_info: %d config files detected", len(detected))
    return [{"type": "text", "value": output}]
