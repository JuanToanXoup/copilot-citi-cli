"""MCP Agent Server — expose a CopilotClient agent session as an MCP server.

Each worker agent runs as an MCP server (stdio transport) that exposes its
capabilities as MCP tools.  The orchestrator connects to workers as an MCP
client using the existing ``MCPServer`` class from ``copilot_cli.mcp``.

This implements the emerging MCP agent-to-agent pattern where agents discover
and invoke each other through the standard MCP tool protocol.

MCP Agent Server Tools
----------------------
Each agent server exposes these MCP tools:

- ``execute_task(prompt, context?)`` — Send a task to the agent, returns the
  agent's reply and tool-call history.
- ``get_status()`` — Check if the agent is idle/busy and get conversation info.
- ``get_capabilities()`` — Return the agent's role, model, available tools,
  and system prompt summary.

Architecture
------------
::

    Orchestrator (MCP Client)
        │
        ├── MCPServer("coder")  ──stdio──►  MCPAgentServer(CopilotClient)
        ├── MCPServer("reviewer") ──stdio──►  MCPAgentServer(CopilotClient)
        └── MCPServer("tester")  ──stdio──►  MCPAgentServer(CopilotClient)

    Each MCPAgentServer:
    1. Starts as a child process
    2. Reads JSON-RPC from stdin (MCP stdio transport)
    3. Handles initialize → tools/list → tools/call
    4. Delegates to an internal CopilotClient for actual work
"""

from __future__ import annotations

import dataclasses
import json
import os
import sys
import threading
import time

# ── Agent Card ────────────────────────────────────────────────────────────────
# Following the emerging MCP agent discovery pattern: each agent has a
# descriptor ("agent card") that advertises its capabilities.

@dataclasses.dataclass
class AgentCard:
    """Descriptor for an MCP-based agent (discovery metadata)."""
    name: str
    role: str
    description: str = ""
    model: str | None = None
    system_prompt: str = ""
    tools_enabled: list[str] | str = "__ALL__"
    agent_mode: bool = True
    version: str = "0.1.0"

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "role": self.role,
            "description": self.description,
            "model": self.model,
            "system_prompt_summary": self.system_prompt[:200] if self.system_prompt else "",
            "tools_enabled": self.tools_enabled,
            "agent_mode": self.agent_mode,
            "version": self.version,
        }


# ── MCP Agent Server (runs in child process) ─────────────────────────────────

# The three tools this agent server exposes via MCP
AGENT_TOOLS = [
    {
        "name": "execute_task",
        "description": "Send a task to this agent for execution. Returns the agent's reply.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "The task description / prompt for the agent",
                },
                "context": {
                    "type": "string",
                    "description": "Optional JSON-encoded shared context from other agents",
                },
            },
            "required": ["prompt"],
        },
    },
    {
        "name": "get_status",
        "description": "Get the current status of this agent (idle/busy, conversation info).",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
    {
        "name": "get_capabilities",
        "description": "Get this agent's capabilities: role, model, available tools.",
        "inputSchema": {
            "type": "object",
            "properties": {},
            "required": [],
        },
    },
]


class MCPAgentServer:
    """An MCP server that wraps a CopilotClient agent session.

    Reads JSON-RPC from stdin, writes to stdout (MCP stdio transport).
    Internally creates a CopilotClient to do actual agent work.

    This is designed to run as the main loop of a child process spawned
    by the orchestrator.
    """

    def __init__(self, agent_card: AgentCard, workspace: str,
                 proxy_url: str | None = None,
                 no_ssl_verify: bool = False):
        self.card = agent_card
        self.workspace = workspace
        self.proxy_url = proxy_url
        self.no_ssl_verify = no_ssl_verify
        self._client = None
        self._conversation_id: str | None = None
        self._status = "idle"  # idle | busy | error
        self._lock = threading.Lock()

    def _send(self, msg: dict):
        """Write a JSON-RPC message to stdout (newline-delimited)."""
        line = json.dumps(msg) + "\n"
        sys.stdout.write(line)
        sys.stdout.flush()

    def _send_response(self, req_id, result):
        self._send({"jsonrpc": "2.0", "id": req_id, "result": result})

    def _send_error(self, req_id, code: int, message: str):
        self._send({
            "jsonrpc": "2.0", "id": req_id,
            "error": {"code": code, "message": message},
        })

    def _init_copilot_client(self):
        """Lazy-init the internal CopilotClient."""
        if self._client is not None:
            return

        from copilot_cli.client import _init_client
        from copilot_cli.tools import TOOL_SCHEMAS, TOOL_EXECUTORS

        # Filter tools if needed
        if self.card.tools_enabled != "__ALL__":
            enabled = set(self.card.tools_enabled)
            for name in list(TOOL_SCHEMAS.keys()):
                if name not in enabled:
                    del TOOL_SCHEMAS[name]
            for name in list(TOOL_EXECUTORS.keys()):
                if name not in enabled:
                    del TOOL_EXECUTORS[name]

        self._client = _init_client(
            self.workspace,
            agent_mode=self.card.agent_mode,
            proxy_url=self.proxy_url,
            no_ssl_verify=self.no_ssl_verify,
        )

    def _handle_execute_task(self, arguments: dict) -> dict:
        """Handle the execute_task MCP tool call."""
        prompt = arguments.get("prompt", "")
        context_str = arguments.get("context", "")

        with self._lock:
            self._status = "busy"

        try:
            self._init_copilot_client()

            # Build the actual prompt
            parts = []
            if self.card.system_prompt and self._conversation_id is None:
                parts.append(
                    f"<system_instructions>{self.card.system_prompt}</system_instructions>"
                )
            if context_str:
                parts.append(f"<shared_context>{context_str}</shared_context>")
            parts.append(prompt)
            actual_prompt = "\n\n".join(parts)

            from copilot_cli.platform_utils import path_to_file_uri
            workspace_uri = path_to_file_uri(self.workspace)

            if self._conversation_id is None:
                result = self._client.conversation_create(
                    actual_prompt,
                    model=self.card.model,
                    agent_mode=self.card.agent_mode,
                    workspace_folder=workspace_uri if self.card.agent_mode else None,
                )
                self._conversation_id = result.get("conversationId")
            else:
                result = self._client.conversation_turn(
                    self._conversation_id, actual_prompt,
                    model=self.card.model,
                    agent_mode=self.card.agent_mode,
                    workspace_folder=workspace_uri if self.card.agent_mode else None,
                )

            reply = result.get("reply", "")
            rounds = result.get("agent_rounds", [])

            return {
                "content": [
                    {
                        "type": "text",
                        "text": json.dumps({
                            "status": "success",
                            "reply": reply,
                            "agent_rounds_count": len(rounds),
                            "worker": self.card.role,
                        }),
                    }
                ],
            }
        except Exception as e:
            return {
                "content": [
                    {
                        "type": "text",
                        "text": json.dumps({
                            "status": "error",
                            "error": str(e),
                            "worker": self.card.role,
                        }),
                    }
                ],
                "isError": True,
            }
        finally:
            with self._lock:
                self._status = "idle"

    def _handle_get_status(self) -> dict:
        with self._lock:
            status = self._status
        return {
            "content": [{
                "type": "text",
                "text": json.dumps({
                    "status": status,
                    "role": self.card.role,
                    "has_conversation": self._conversation_id is not None,
                }),
            }],
        }

    def _handle_get_capabilities(self) -> dict:
        return {
            "content": [{
                "type": "text",
                "text": json.dumps(self.card.to_dict()),
            }],
        }

    def serve_forever(self):
        """Main loop: read JSON-RPC from stdin and respond.

        This blocks forever and is intended to be the child process main loop.
        """
        # Redirect our own stderr so CopilotClient prints don't corrupt the
        # MCP stdio channel.  MCP clients can read stderr for diagnostics.
        _original_stdout = sys.stdout
        sys.stdout = sys.stderr  # CopilotClient prints go to stderr
        self._real_stdout = _original_stdout  # MCP messages go here

        # Override _send to use the real stdout
        def _send_to_real(msg: dict):
            line = json.dumps(msg) + "\n"
            _original_stdout.write(line)
            _original_stdout.flush()
        self._send = _send_to_real

        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue

            req_id = msg.get("id")
            method = msg.get("method", "")
            params = msg.get("params", {})

            if method == "initialize":
                self._send_response(req_id, {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {
                        "name": f"mcp-agent-{self.card.role}",
                        "version": self.card.version,
                    },
                })

            elif method == "notifications/initialized":
                pass  # No response needed for notifications

            elif method == "tools/list":
                self._send_response(req_id, {"tools": AGENT_TOOLS})

            elif method == "tools/call":
                tool_name = params.get("name", "")
                arguments = params.get("arguments", {})

                if tool_name == "execute_task":
                    result = self._handle_execute_task(arguments)
                elif tool_name == "get_status":
                    result = self._handle_get_status()
                elif tool_name == "get_capabilities":
                    result = self._handle_get_capabilities()
                else:
                    self._send_error(req_id, -32601, f"Unknown tool: {tool_name}")
                    continue

                self._send_response(req_id, result)

            elif req_id is not None:
                # Unknown method with an id — respond with error
                self._send_error(req_id, -32601, f"Method not found: {method}")

        # stdin closed — clean up
        self._shutdown()

    def _shutdown(self):
        if self._client:
            if self._conversation_id:
                try:
                    self._client.conversation_destroy(self._conversation_id)
                except Exception:
                    pass
            try:
                self._client.stop()
            except Exception:
                pass


# ── Child process entry point ─────────────────────────────────────────────────

def _agent_server_main():
    """Entry point when this module is run as a child process.

    Expects a JSON config on the first line of stdin (before MCP messages),
    or as the first CLI argument.

    Config format:
    {
        "role": "coder",
        "name": "Coder Agent",
        "description": "...",
        "system_prompt": "...",
        "model": "gpt-4.1",
        "tools_enabled": "__ALL__",
        "agent_mode": true,
        "workspace": "/path/to/workspace",
        "proxy_url": null,
        "no_ssl_verify": false
    }
    """
    # Read config from CLI arg or environment
    config_json = None
    if len(sys.argv) > 1:
        config_json = sys.argv[1]
    elif "MCP_AGENT_CONFIG" in os.environ:
        config_json = os.environ["MCP_AGENT_CONFIG"]
    else:
        # Read first line from stdin as config
        config_json = sys.stdin.readline().strip()

    config = json.loads(config_json)

    card = AgentCard(
        name=config.get("name", config.get("role", "agent")),
        role=config.get("role", "worker"),
        description=config.get("description", ""),
        model=config.get("model"),
        system_prompt=config.get("system_prompt", ""),
        tools_enabled=config.get("tools_enabled", "__ALL__"),
        agent_mode=config.get("agent_mode", True),
    )

    server = MCPAgentServer(
        agent_card=card,
        workspace=config.get("workspace", os.getcwd()),
        proxy_url=config.get("proxy_url"),
        no_ssl_verify=config.get("no_ssl_verify", False),
    )
    server.serve_forever()


if __name__ == "__main__":
    _agent_server_main()
