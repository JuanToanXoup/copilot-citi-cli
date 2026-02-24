"""Cross-platform helpers for paths, file URIs, and tool discovery."""

import glob
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path


def path_to_file_uri(path: str) -> str:
    """Convert an absolute path to a proper file:// URI on any OS."""
    return Path(os.path.abspath(path)).as_uri()


def _binary_search_globs() -> list[str]:
    """Return all candidate globs for copilot-language-server, ordered by preference."""
    globs = []
    if sys.platform == "win32":
        exe = "copilot-language-server.exe"
        # JetBrains IDEs
        local = os.environ.get("LOCALAPPDATA", os.path.expanduser("~/AppData/Local"))
        globs.append(os.path.join(local, "JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/win32-x64", exe))
        globs.append(os.path.join(local, "Programs/cursor*/resources/app/extensions/github-copilot/dist", exe))
        # VS Code
        home = os.path.expanduser("~")
        globs.append(os.path.join(home, ".vscode/extensions/github.copilot-*/dist", exe))
        globs.append(os.path.join(home, ".vscode-insiders/extensions/github.copilot-*/dist", exe))
        globs.append(os.path.join(home, ".cursor/extensions/github.copilot-*/dist", exe))
    elif sys.platform == "linux":
        home = os.path.expanduser("~")
        globs.append(os.path.join(home, ".local/share/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/linux-x64/copilot-language-server"))
        globs.append(os.path.join(home, ".vscode/extensions/github.copilot-*/dist/copilot-language-server"))
        globs.append(os.path.join(home, ".vscode-insiders/extensions/github.copilot-*/dist/copilot-language-server"))
        globs.append(os.path.join(home, ".cursor/extensions/github.copilot-*/dist/copilot-language-server"))
    else:
        # macOS
        arch = "darwin-arm64" if platform.machine() == "arm64" else "darwin-x64"
        app_support = os.path.expanduser("~/Library/Application Support")
        globs.append(os.path.join(app_support, f"JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/{arch}/copilot-language-server"))
        globs.append(os.path.join(app_support, f"Cursor/User/globalStorage/github.copilot-*/dist/copilot-language-server"))
        home = os.path.expanduser("~")
        globs.append(os.path.join(home, ".vscode/extensions/github.copilot-*/dist/copilot-language-server"))
        globs.append(os.path.join(home, ".vscode-insiders/extensions/github.copilot-*/dist/copilot-language-server"))
    return globs


def _apps_json_candidates() -> list[str]:
    """Return all candidate paths for apps.json, ordered by preference."""
    candidates = []
    if sys.platform == "win32":
        appdata = os.environ.get("APPDATA", os.path.expanduser("~/AppData/Roaming"))
        candidates.append(os.path.join(appdata, "github-copilot", "apps.json"))
    else:
        candidates.append(os.path.expanduser("~/.config/github-copilot/apps.json"))
    return candidates


def discover_copilot_binary() -> str | None:
    """Auto-discover the copilot-language-server binary.

    Searches all known install locations (JetBrains, VS Code, Cursor)
    and returns the newest match, or None if not found.
    """
    all_matches = []
    for pattern in _binary_search_globs():
        all_matches.extend(glob.glob(pattern))
    if not all_matches:
        return None
    # Pick the newest by modification time
    all_matches.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return all_matches[0]


def discover_apps_json() -> str | None:
    """Auto-discover the apps.json auth file.

    Returns the path if it exists, or None.
    """
    for candidate in _apps_json_candidates():
        if os.path.isfile(candidate):
            return candidate
    return None


def default_copilot_binary() -> str:
    """Return the copilot-language-server path: auto-discovered or fallback glob."""
    found = discover_copilot_binary()
    if found:
        return found
    # Fallback: return a glob pattern for _get_config_value to expand
    if sys.platform == "win32":
        local = os.environ.get("LOCALAPPDATA", os.path.expanduser("~/AppData/Local"))
        return os.path.join(local, "JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/win32-x64/copilot-language-server.exe")
    elif sys.platform == "linux":
        return os.path.expanduser("~/.local/share/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/linux-x64/copilot-language-server")
    else:
        arch = "darwin-arm64" if platform.machine() == "arm64" else "darwin-x64"
        return os.path.expanduser(f"~/Library/Application Support/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/{arch}/copilot-language-server")


def default_apps_json() -> str:
    """Return the apps.json path: auto-discovered or fallback default."""
    found = discover_apps_json()
    if found:
        return found
    if sys.platform == "win32":
        return os.path.join(os.environ.get("APPDATA", os.path.expanduser("~/AppData/Roaming")), "github-copilot", "apps.json")
    return os.path.expanduser("~/.config/github-copilot/apps.json")


def find_grep() -> str | None:
    """Return the path to grep if available, or None."""
    return shutil.which("grep")


def _detect_linux_terminal() -> list[str] | None:
    """Detect the best available terminal emulator on Linux."""
    # Prefer $TERMINAL env var, then common terminals in priority order
    env_term = os.environ.get("TERMINAL")
    if env_term and shutil.which(env_term):
        return [env_term, "-e"]

    terminals = [
        (["gnome-terminal", "--"], "gnome-terminal"),
        (["konsole", "-e"], "konsole"),
        (["xfce4-terminal", "-e"], "xfce4-terminal"),
        (["mate-terminal", "-e"], "mate-terminal"),
        (["xterm", "-e"], "xterm"),
    ]
    for cmd_prefix, binary in terminals:
        if shutil.which(binary):
            return cmd_prefix
    return None


def open_in_system_terminal(binary_path: str, cwd: str | None = None) -> None:
    """Launch an executable in the OS native terminal emulator.

    Args:
        binary_path: Absolute path to the executable to run.
        cwd: Working directory for the launched process.
             Defaults to the binary's parent directory.

    Raises:
        RuntimeError: If no suitable terminal emulator is found.
        FileNotFoundError: If binary_path does not exist.
    """
    binary_path = os.path.abspath(binary_path)
    if not os.path.isfile(binary_path):
        raise FileNotFoundError(f"Binary not found: {binary_path}")
    if cwd is None:
        cwd = os.path.dirname(binary_path)

    if sys.platform == "darwin":
        # macOS: use open -a Terminal with a helper script
        script = (
            f'tell application "Terminal"\n'
            f'  do script "cd {_shell_quote(cwd)} && {_shell_quote(binary_path)}"\n'
            f'  activate\n'
            f'end tell'
        )
        subprocess.Popen(["osascript", "-e", script])

    elif sys.platform == "win32":
        # Windows: launch in a new cmd.exe window
        subprocess.Popen(
            ["cmd.exe", "/c", "start", "cmd.exe", "/k", binary_path],
            cwd=cwd,
        )

    else:
        # Linux: detect and use system terminal emulator
        term_cmd = _detect_linux_terminal()
        if term_cmd is None:
            raise RuntimeError(
                "No terminal emulator found. Install one of: "
                "gnome-terminal, konsole, xfce4-terminal, xterm "
                "or set the TERMINAL environment variable."
            )
        subprocess.Popen([*term_cmd, binary_path], cwd=cwd)


def _shell_quote(s: str) -> str:
    """Quote a string for safe embedding in a shell command."""
    return "'" + s.replace("'", "'\\''") + "'"
