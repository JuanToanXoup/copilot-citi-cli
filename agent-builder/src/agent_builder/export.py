"""Agent export — generate standalone entry points and PyInstaller binaries."""

import json
import os
import shutil
import subprocess
import sys
import textwrap


def _sanitize_name(name: str) -> str:
    """Sanitize agent name for use as filename/identifier."""
    return "".join(c for c in name if c.isalnum() or c in "-_").strip() or "agent"


def _generate_entry_point(config: dict, output_dir: str) -> str:
    """Generate a standalone Python entry script that embeds the agent config.

    Returns the path to the generated entry point.
    """
    name = _sanitize_name(config.get("name", "agent"))
    entry_path = os.path.join(output_dir, f"_agent_{name}_entry.py")

    tools_enabled = config.get("tools", {}).get("enabled", "__ALL__")
    model = config.get("model", "gpt-4.1")
    system_prompt = config.get("system_prompt", "")
    description = config.get("description", "")
    agent_mode = config.get("agent_mode", True)

    # Escape for embedding in triple-quoted string
    system_prompt_escaped = system_prompt.replace("\\", "\\\\").replace('"""', '\\"\\"\\"')
    description_escaped = description.replace("\\", "\\\\").replace('"""', '\\"\\"\\"')

    config_json_str = json.dumps(json.dumps(config))  # double-encode: JSON string literal

    script = f'''#!/usr/bin/env python3
"""Auto-generated agent entry point: {name}"""

import json
import os
import sys
import threading
import time

try:
    import readline  # noqa: F401 – enables arrow-key editing in input()
except ImportError:
    pass

# Ensure the parent directory is on the path so imports work
_here = os.path.dirname(os.path.abspath(__file__))
_parent = os.path.dirname(_here)
if _parent not in sys.path:
    sys.path.insert(0, _parent)
if _here not in sys.path:
    sys.path.insert(0, _here)

AGENT_CONFIG = json.loads({config_json_str})

def _filter_tools():
    """Restrict TOOL_SCHEMAS/TOOL_EXECUTORS to only enabled tools."""
    from copilot_cli.tools import TOOL_SCHEMAS, TOOL_EXECUTORS
    enabled = AGENT_CONFIG.get("tools", {{}}).get("enabled", "__ALL__")
    if enabled == "__ALL__":
        return  # all tools allowed
    enabled_set = set(enabled)
    for name in list(TOOL_SCHEMAS.keys()):
        if name not in enabled_set:
            del TOOL_SCHEMAS[name]
    for name in list(TOOL_EXECUTORS.keys()):
        if name not in enabled_set:
            del TOOL_EXECUTORS[name]

def main():
    _filter_tools()

    from copilot_cli.client import _init_client, CopilotClient, release_client
    from copilot_cli.tools import TOOL_SCHEMAS, BUILTIN_TOOL_NAMES
    from copilot_cli.platform_utils import path_to_file_uri

    config = AGENT_CONFIG
    name = config.get("name", "Agent")
    description = """{description_escaped}"""
    model = config.get("model", "gpt-4.1")
    agent_mode = config.get("agent_mode", True)
    workspace = os.path.abspath(config.get("workspace_root") or os.getcwd())
    system_prompt = """{system_prompt_escaped}"""

    # MCP / LSP / proxy
    mcp_config = config.get("mcp_servers") or None
    lsp_config = config.get("lsp_servers") or None
    proxy_cfg = config.get("proxy", {{}})
    proxy_url = proxy_cfg.get("url") if proxy_cfg else None
    no_ssl_verify = proxy_cfg.get("no_ssl_verify", False) if proxy_cfg else False

    # Banner
    tool_count = len(TOOL_SCHEMAS) + len(BUILTIN_TOOL_NAMES)
    print()
    print(f"  \\033[94m╭─ {{name}}\\033[0m")
    if description:
        print(f"  \\033[94m│\\033[0m  {{description}}")
    print(f"  \\033[94m│\\033[0m  {{model}} · {{tool_count}} tools")
    print(f"  \\033[94m│\\033[0m  \\033[90m{{os.path.basename(workspace)}}\\033[0m")
    print(f"  \\033[94m╰─\\033[0m")
    print()

    client = _init_client(
        workspace,
        agent_mode=agent_mode,
        mcp_config=mcp_config,
        lsp_config=lsp_config,
        proxy_url=proxy_url,
        no_ssl_verify=no_ssl_verify,
    )

    try:
        workspace_uri = path_to_file_uri(workspace)
        conversation_id = None

        while True:
            try:
                cols = os.get_terminal_size().columns
            except OSError:
                cols = 80
            sep = "\\033[90m" + "─" * cols + "\\033[0m"
            print(sep)
            try:
                sys.stdout.write("\\033[1m❯\\033[0m ")
                sys.stdout.flush()
                prompt = input().strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break
            print(sep)
            print("\\033[A\\033[2K" * 3, end="")
            print(f"\\033[100m\\033[97m ❯ {{prompt}} \\033[0m")

            if not prompt:
                continue
            if prompt.lower() in ("exit", "quit", "/quit", "/exit"):
                break

            # Prepend system prompt on first turn
            actual_msg = prompt
            if system_prompt and conversation_id is None:
                actual_msg = f"<system_instructions>{{system_prompt}}</system_instructions>\\n\\n{{prompt}}"

            spinner_stop = threading.Event()
            streaming_started = threading.Event()

            def _clear_spinner():
                if not spinner_stop.is_set():
                    spinner_stop.set()
                    print("\\r\\033[K", flush=True)

            client._spinner_clear = _clear_spinner

            def _spinner():
                chars = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
                i = 0
                while not spinner_stop.is_set():
                    print(f"\\r\\033[33m{{chars[i % len(chars)]}} thinking...\\033[0m", end="", flush=True)
                    i += 1
                    spinner_stop.wait(0.1)

            def _on_progress(kind, data):
                _clear_spinner()
                if kind == "delta":
                    delta = data.get("delta", "")
                    if delta:
                        if not streaming_started.is_set():
                            streaming_started.set()
                            print(f"\\033[94m⏺\\033[0m ", end="", flush=True)
                        print(delta, end="", flush=True)
                elif kind == "done":
                    if streaming_started.is_set():
                        print()

            spinner_thread = threading.Thread(target=_spinner, daemon=True)
            spinner_thread.start()

            if conversation_id is None:
                result = client.conversation_create(
                    actual_msg, model=model, agent_mode=agent_mode,
                    workspace_folder=workspace_uri if agent_mode else None,
                    on_progress=_on_progress,
                )
                conversation_id = result["conversationId"]
            else:
                result = client.conversation_turn(
                    conversation_id, actual_msg, model=model,
                    agent_mode=agent_mode, on_progress=_on_progress,
                )

            _clear_spinner()
            client._spinner_clear = None
            spinner_thread.join(timeout=1)

            if not streaming_started.is_set() and result.get("reply"):
                print(f"\\033[94m⏺\\033[0m {{result['reply']}}")

            print()

        if conversation_id:
            client.conversation_destroy(conversation_id)
    finally:
        release_client(client)

if __name__ == "__main__":
    main()
'''

    with open(entry_path, "w") as f:
        f.write(script)
    os.chmod(entry_path, 0o755)

    return entry_path


def _generate_orchestrator_entry_point(config: dict, output_dir: str) -> str:
    """Generate a standalone orchestrator entry script with embedded worker configs.

    Returns the path to the generated entry point.
    """
    name = _sanitize_name(config.get("name", "agent"))
    entry_path = os.path.join(output_dir, f"_agent_{name}_entry.py")

    config_json_str = json.dumps(json.dumps(config))

    script = f'''#!/usr/bin/env python3
"""Auto-generated orchestrator entry point: {name}"""

import json
import os
import sys

# Ensure the parent directory is on the path so imports work
_here = os.path.dirname(os.path.abspath(__file__))
_parent = os.path.dirname(_here)
if _parent not in sys.path:
    sys.path.insert(0, _parent)
if _here not in sys.path:
    sys.path.insert(0, _here)

AGENT_CONFIG = json.loads({config_json_str})


def main():
    from copilot_cli.orchestrator import run_orchestrator_cli, WorkerConfig

    config = AGENT_CONFIG
    name = config.get("name", "Orchestrator")
    description = config.get("description", "")
    model = config.get("model")
    transport = config.get("transport", "mcp")
    workspace = os.path.abspath(config.get("workspace_root") or os.getcwd())

    # Parse embedded worker definitions
    raw_workers = config.get("workers", [])
    workers = [
        WorkerConfig(
            role=w["role"],
            system_prompt=w.get("system_prompt", ""),
            model=w.get("model", model),
            tools_enabled=w.get("tools_enabled", "__ALL__"),
            agent_mode=w.get("agent_mode", True),
            workspace_root=w.get("workspace_root"),
            proxy_url=w.get("proxy_url"),
            no_ssl_verify=w.get("no_ssl_verify"),
            mcp_servers=w.get("mcp_servers"),
            lsp_servers=w.get("lsp_servers"),
        )
        for w in raw_workers
    ]

    # Banner
    print()
    print(f"  \\033[94m╭─ {{name}}\\033[0m")
    if description:
        print(f"  \\033[94m│\\033[0m  {{description}}")
    print(f"  \\033[94m│\\033[0m  {{model or 'default'}} · {{transport}} · {{len(workers)}} workers")
    for w in workers:
        print(f"  \\033[94m│\\033[0m    \\033[90m{{w.role}}\\033[0m")
    print(f"  \\033[94m│\\033[0m  \\033[90m{{os.path.basename(workspace)}}\\033[0m")
    print(f"  \\033[94m╰─\\033[0m")
    print()

    # Get goal from command line or interactive prompt
    if len(sys.argv) > 1:
        goal = " ".join(sys.argv[1:])
    else:
        sys.stdout.write("\\033[1m❯\\033[0m Goal: ")
        sys.stdout.flush()
        try:
            goal = input().strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return
        if not goal:
            print("[!] No goal provided.")
            return

    # MCP / LSP / Proxy
    mcp_config = config.get("mcp_servers") or None
    lsp_config = config.get("lsp_servers") or None
    proxy_cfg = config.get("proxy", {{}})
    proxy_url = proxy_cfg.get("url") if proxy_cfg else None
    no_ssl_verify = proxy_cfg.get("no_ssl_verify", False) if proxy_cfg else False

    run_orchestrator_cli(
        workspace=workspace,
        goal=goal,
        workers=workers,
        model=model,
        transport=transport,
        proxy_url=proxy_url,
        no_ssl_verify=no_ssl_verify,
        mcp_config=mcp_config,
        lsp_config=lsp_config,
    )


if __name__ == "__main__":
    main()
'''

    with open(entry_path, "w") as f:
        f.write(script)
    os.chmod(entry_path, 0o755)

    return entry_path


def _is_orchestrator_config(config: dict) -> bool:
    """Return True if config defines an orchestrator with worker agents."""
    return bool(config.get("workers"))


def export_script(config: dict, output_dir: str) -> tuple[str, str]:
    """Export agent as a .py script + config.json (no PyInstaller).

    Returns (entry_point_path, config_path).
    """
    name = _sanitize_name(config.get("name", "agent"))
    if _is_orchestrator_config(config):
        entry_path = _generate_orchestrator_entry_point(config, output_dir)
    else:
        entry_path = _generate_entry_point(config, output_dir)

    config_path = os.path.join(output_dir, f"{name}_config.json")
    with open(config_path, "w") as f:
        json.dump(config, f, indent=2)

    return entry_path, config_path


def build_agent(config: dict, output_dir: str,
                on_progress: callable = None) -> str:
    """Build a standalone PyInstaller binary from an agent config.

    Args:
        config: Agent configuration dict.
        output_dir: Directory for build artifacts.
        on_progress: Callback ``(step_type, message)`` — step_type is
            "step" for major phases, "log" for detail, "error" for failures.

    Returns:
        Path to the built binary.
    """
    def _emit(step_type, msg):
        if on_progress:
            on_progress(step_type, msg)

    name = _sanitize_name(config.get("name", "agent"))
    _emit("step", f"Generating entry point for '{name}'...")

    if _is_orchestrator_config(config):
        entry_path = _generate_orchestrator_entry_point(config, output_dir)
        _emit("log", f"  Orchestrator entry point: {entry_path}")
    else:
        entry_path = _generate_entry_point(config, output_dir)
        _emit("log", f"  Entry point: {entry_path}")

    # Locate package roots for PyInstaller --paths
    project_root = os.path.dirname(os.path.abspath(__file__))  # agent_builder pkg
    # copilot_cli lives in cli/src/ relative to the repo root
    repo_root = os.path.normpath(os.path.join(project_root, "..", "..", ".."))
    cli_src = os.path.join(repo_root, "cli", "src")

    # Check for PyInstaller — try each candidate and verify it works
    _emit("step", "Checking for PyInstaller...")
    pyinstaller_cmd = None
    candidates = []
    pyinstaller_bin = shutil.which("pyinstaller")
    if pyinstaller_bin:
        candidates.append([pyinstaller_bin])
    candidates.append([sys.executable, "-m", "PyInstaller"])
    for candidate in candidates:
        try:
            subprocess.run(
                [*candidate, "--version"],
                capture_output=True, check=True,
            )
            pyinstaller_cmd = candidate
            break
        except (subprocess.CalledProcessError, FileNotFoundError):
            continue
    if not pyinstaller_cmd:
        _emit("error", "PyInstaller not found (or broken). Install with: pip install pyinstaller")
        raise RuntimeError("PyInstaller not installed")

    # Build add-data args
    sep = ";" if os.name == "nt" else ":"
    add_data = []

    tools_dir = os.path.join(project_root, "tools")
    if os.path.isdir(tools_dir):
        add_data.extend(["--add-data", f"{tools_dir}{sep}tools"])

    config_toml = os.path.join(project_root, "copilot_config.toml")
    if os.path.isfile(config_toml):
        add_data.extend(["--add-data", f"{config_toml}{sep}."])

    # Hidden imports
    hidden = [
        "--hidden-import", "copilot_cli",
        "--hidden-import", "copilot_cli.client",
        "--hidden-import", "copilot_cli.mcp",
        "--hidden-import", "copilot_cli.lsp_bridge",
        "--hidden-import", "copilot_cli.platform_utils",
        "--hidden-import", "copilot_cli.log",
        "--hidden-import", "copilot_cli.tools",
        "--hidden-import", "agent_builder.templates",
        "--hidden-import", "readline",
    ]

    cmd = [
        *pyinstaller_cmd,
        "--onefile",
        "--console",
        "--name", name,
        "--distpath", output_dir,
        "--workpath", os.path.join(output_dir, "build"),
        "--specpath", os.path.join(output_dir, "build"),
        *add_data,
        *hidden,
        "--paths", project_root,
        "--paths", cli_src,
        entry_path,
    ]

    _emit("step", "Running PyInstaller...")
    _emit("log", f"  Command: {' '.join(cmd)}")

    proc = subprocess.Popen(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, cwd=project_root,
    )

    for line in proc.stdout:
        line = line.rstrip()
        if line:
            _emit("log", line)

    proc.wait()
    if proc.returncode != 0:
        _emit("error", f"PyInstaller exited with code {proc.returncode}")
        raise RuntimeError(f"PyInstaller failed (exit code {proc.returncode})")

    # Find the binary
    binary_name = name + (".exe" if os.name == "nt" else "")
    binary_path = os.path.join(output_dir, binary_name)
    if not os.path.isfile(binary_path):
        _emit("error", f"Binary not found at {binary_path}")
        raise RuntimeError(f"Binary not found: {binary_path}")

    _emit("step", f"Build complete: {binary_path}")

    # Cleanup temp entry point
    try:
        os.remove(entry_path)
    except OSError:
        pass

    return binary_path
