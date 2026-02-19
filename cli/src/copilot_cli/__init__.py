"""Copilot CLI — GitHub Copilot Language Server client."""

from copilot_cli.client import CopilotClient, _init_client, _load_config, release_client, SessionPool

__all__ = ["CopilotClient", "_init_client", "_load_config", "release_client", "SessionPool"]
