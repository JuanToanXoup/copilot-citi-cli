"""Cross-platform helpers for paths, file URIs, and tool discovery."""

import os
import shutil
import sys
from pathlib import Path


def path_to_file_uri(path: str) -> str:
    """Convert an absolute path to a proper file:// URI on any OS."""
    return Path(os.path.abspath(path)).as_uri()


def default_copilot_binary() -> str:
    """Return the default copilot-language-server glob for the current OS."""
    if sys.platform == "win32":
        return (
            "~/AppData/Local/JetBrains/*/plugins/"
            "github-copilot-intellij/copilot-agent/native/win32-x64/"
            "copilot-language-server.exe"
        )
    elif sys.platform == "linux":
        return (
            "~/.local/share/JetBrains/*/plugins/"
            "github-copilot-intellij/copilot-agent/native/linux-x64/"
            "copilot-language-server"
        )
    else:
        # macOS — detect arm64 vs x64
        import platform
        arch = "darwin-arm64" if platform.machine() == "arm64" else "darwin-x64"
        return (
            f"~/Library/Application Support/JetBrains/*/plugins/"
            f"github-copilot-intellij/copilot-agent/native/{arch}/"
            f"copilot-language-server"
        )


def default_apps_json() -> str:
    """Return the default apps.json path for the current OS."""
    if sys.platform == "win32":
        return os.path.join(os.environ.get("APPDATA", "~"), "github-copilot", "apps.json")
    return "~/.config/github-copilot/apps.json"


def find_grep() -> str | None:
    """Return the path to grep if available, or None."""
    return shutil.which("grep")
