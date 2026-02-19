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
    question_schema: dict | None = None  # Input schema (what the worker accepts)
    answer_schema: dict | None = None    # Output schema (what the worker returns)

    def to_dict(self) -> dict:
        d = {
            "name": self.name,
            "role": self.role,
            "description": self.description,
            "model": self.model,
            "system_prompt_summary": self.system_prompt[:200] if self.system_prompt else "",
            "tools_enabled": self.tools_enabled,
            "agent_mode": self.agent_mode,
            "version": self.version,
        }
        if self.question_schema:
            d["question_schema"] = self.question_schema
        if self.answer_schema:
            d["answer_schema"] = self.answer_schema
        return d


# ── MCP Agent Server (runs in child process) ─────────────────────────────────


def _build_agent_tools(card: AgentCard) -> list[dict]:
    """Build the MCP tool definitions for this agent.

    When the agent has a ``question_schema``, the ``execute_task`` tool's
    ``inputSchema`` is enriched with the schema fields so the orchestrator
    LLM sees a typed contract instead of a generic ``prompt`` string.

    When the agent has an ``answer_schema``, the tool description includes
    the expected response structure.
    """
    from copilot_cli.schema_validation import (
        schema_to_json_schema,
        schema_to_description,
    )

    # Build execute_task input schema
    exec_properties = {
        "prompt": {
            "type": "string",
            "description": "The task description / prompt for the agent",
        },
        "context": {
            "type": "string",
            "description": "Optional JSON-encoded shared context from other agents",
        },
    }
    exec_required = ["prompt"]

    # If the worker defines a question_schema, add those fields as
    # additional structured parameters alongside the free-form prompt
    if card.question_schema:
        q_schema = schema_to_json_schema(card.question_schema)
        for name, prop in q_schema.get("properties", {}).items():
            exec_properties[name] = prop
        for name in q_schema.get("required", []):
            if name not in exec_required:
                exec_required.append(name)

    # Build description with answer schema hint
    exec_desc = (
        f"Send a task to the {card.role} agent for execution. "
        f"Returns the agent's reply."
    )
    if card.answer_schema:
        answer_desc = schema_to_description(card.answer_schema, "Expected response fields")
        exec_desc += f"\n\n{answer_desc}"

    return [
        {
            "name": "execute_task",
            "description": exec_desc,
            "inputSchema": {
                "type": "object",
                "properties": exec_properties,
                "required": exec_required,
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
                 no_ssl_verify: bool = False,
                 mcp_config: dict | None = None,
                 lsp_config: dict | None = None):
        self.card = agent_card
        self.workspace = workspace
        self.proxy_url = proxy_url
        self.no_ssl_verify = no_ssl_verify
        self.mcp_config = mcp_config
        self.lsp_config = lsp_config
        self._client = None
        self._conversation_id: str | None = None
        self._status = "idle"  # idle | busy | error
        self._lock = threading.Lock()
        self._agent_tools = _build_agent_tools(agent_card)

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
            mcp_config=self.mcp_config,
            lsp_config=self.lsp_config,
            proxy_url=self.proxy_url,
            no_ssl_verify=self.no_ssl_verify,
        )

    def _handle_execute_task(self, arguments: dict) -> dict:
        """Handle the execute_task MCP tool call.

        When the worker has a ``question_schema``, structured fields from
        ``arguments`` are injected into the prompt as ``<structured_input>``.

        When the worker has an ``answer_schema``, the prompt includes
        guidance on the expected response format, and the raw reply is
        soft-validated against the schema.
        """
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

            # Inject structured question fields if schema is defined
            if self.card.question_schema:
                structured = {}
                for field_name in self.card.question_schema:
                    if field_name in arguments and field_name not in ("prompt", "context"):
                        structured[field_name] = arguments[field_name]
                if structured:
                    parts.append(
                        f"<structured_input>{json.dumps(structured, indent=2)}</structured_input>"
                    )

            parts.append(prompt)

            # Add answer format guidance if schema is defined
            if self.card.answer_schema:
                from copilot_cli.schema_validation import schema_to_description
                answer_desc = schema_to_description(
                    self.card.answer_schema, "Expected response format"
                )
                parts.append(
                    f"\n<response_format>\n"
                    f"Please structure your response as JSON with these fields:\n"
                    f"{answer_desc}\n"
                    f"You may include additional fields beyond these. "
                    f"Wrap the JSON in ```json fences.\n"
                    f"</response_format>"
                )

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

            # Soft-validate the reply against the answer schema
            response_data = {
                "status": "success",
                "reply": reply,
                "agent_rounds_count": len(rounds),
                "worker": self.card.role,
            }
            if self.card.answer_schema:
                parsed_reply = self._extract_json_from_reply(reply)
                if parsed_reply is not None:
                    from copilot_cli.schema_validation import soft_validate
                    validation = soft_validate(parsed_reply, self.card.answer_schema)
                    response_data["structured_reply"] = validation["parsed"]
                    if validation["extras"]:
                        response_data["structured_reply"].update(validation["extras"])
                    if validation["warnings"]:
                        response_data["validation_warnings"] = validation["warnings"]

            return {
                "content": [
                    {
                        "type": "text",
                        "text": json.dumps(response_data),
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

    @staticmethod
    def _extract_json_from_reply(reply: str) -> dict | None:
        """Best-effort extract a JSON object from an LLM reply.

        Handles bare JSON, ```json fenced blocks, and mixed prose + JSON.
        Returns None if no JSON object can be found.
        """
        text = reply.strip()

        # Try bare JSON first
        if text.startswith("{"):
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                pass

        # Try ```json fenced blocks
        if "```json" in text:
            block = text.split("```json", 1)[1]
            block = block.split("```", 1)[0].strip()
            try:
                return json.loads(block)
            except json.JSONDecodeError:
                pass

        # Try generic ``` fenced blocks
        if "```" in text:
            block = text.split("```", 1)[1]
            block = block.split("```", 1)[0].strip()
            try:
                return json.loads(block)
            except json.JSONDecodeError:
                pass

        # Try to find a JSON object anywhere in the text
        start = text.find("{")
        if start >= 0:
            depth = 0
            for i in range(start, len(text)):
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                    if depth == 0:
                        try:
                            return json.loads(text[start:i + 1])
                        except json.JSONDecodeError:
                            break

        return None

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
                self._send_response(req_id, {"tools": self._agent_tools})

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
                from copilot_cli.client import release_client
                release_client(self._client)
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
        question_schema=config.get("question_schema"),
        answer_schema=config.get("answer_schema"),
    )

    server = MCPAgentServer(
        agent_card=card,
        workspace=config.get("workspace", os.getcwd()),
        proxy_url=config.get("proxy_url"),
        no_ssl_verify=config.get("no_ssl_verify", False),
        mcp_config=config.get("mcp_servers"),
        lsp_config=config.get("lsp_servers"),
    )
    server.serve_forever()


if __name__ == "__main__":
    _agent_server_main()
