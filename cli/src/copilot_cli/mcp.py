"""
Client-side MCP (Model Context Protocol) bridge.

Spawns MCP server processes directly, discovers their tools via the MCP protocol,
and exposes them as client tools that can be registered with the Copilot language
server via conversation/registerTools.

This bypasses any server-side MCP policy restrictions - the language server sees
these as regular client tools, not MCP tools.

MCP stdio transport uses newline-delimited JSON-RPC 2.0 (one JSON object per
line, terminated by \n). This is different from LSP which uses Content-Length
headers.
"""

import json
import os
import shutil
import subprocess
import threading
import time

class MCPServer:
    """Manages a single MCP server process and communicates via JSON-RPC over stdio.

    MCP stdio transport: newline-delimited JSON-RPC 2.0.
    Each message is a single line of JSON followed by a newline character.
    """

    def __init__(self, name: str, command: str, args: list = None, env: dict = None):
        self.name = name
        self.command = command
        self.args = args or []
        self.env = env or {}
        self.process = None
        self.tools = []  # Discovered MCP tools
        self._responses = {}
        self._request_id = 0
        self._lock = threading.Lock()
        self._reader_thread = None

    def start(self):
        """Spawn the MCP server process."""
        env = os.environ.copy()
        env.update(self.env)

        # Resolve command via PATH so .cmd/.bat wrappers work on Windows
        resolved = shutil.which(self.command) or self.command
        self.process = subprocess.Popen(
            [resolved] + self.args,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        self._reader_thread = threading.Thread(target=self._reader_loop, daemon=True)
        self._reader_thread.start()

        # Forward stderr so npx download progress is visible
        def _stderr_reader():
            while self.process and self.process.poll() is None:
                try:
                    line = self.process.stderr.readline()
                except Exception:
                    break
                if not line:
                    break
                text = line.decode("utf-8", errors="replace").rstrip()
                if text:
                    print(f"[client-mcp:{self.name}] {text}")
        threading.Thread(target=_stderr_reader, daemon=True).start()

    def _next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    def _reader_loop(self):
        """Read newline-delimited JSON-RPC messages from stdout."""
        while self.process and self.process.poll() is None:
            try:
                line = self.process.stdout.readline()
            except Exception as e:
                print(f"[client-mcp:{self.name}] read error: {e}")
                break
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue

            with self._lock:
                msg_id = msg.get("id")
                if msg_id is not None and "method" not in msg:
                    # Response to our request
                    self._responses[msg_id] = msg
                elif "method" in msg and msg_id is not None:
                    # Server->client request - auto-respond
                    self._send_raw({"jsonrpc": "2.0", "id": msg_id, "result": {}})

    def _send_raw(self, msg: dict):
        """Send a newline-delimited JSON-RPC message."""
        line = json.dumps(msg) + "\n"
        try:
            self.process.stdin.write(line.encode("utf-8"))
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    def send_request(self, method: str, params: dict = None, timeout: int = 30) -> dict:
        msg_id = self._next_id()
        msg = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            msg["params"] = params
        self._send_raw(msg)

        start = time.time()
        while time.time() - start < timeout:
            with self._lock:
                if msg_id in self._responses:
                    return self._responses.pop(msg_id)
            time.sleep(0.05)
        raise TimeoutError(f"MCP server '{self.name}': no response for {method}")

    def send_notification(self, method: str, params: dict = None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self._send_raw(msg)

    def initialize(self):
        """Perform MCP initialize handshake."""
        resp = self.send_request("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {
                "name": "copilot-cli-mcp-bridge",
                "version": "0.1.0",
            },
        }, timeout=60)
        result = resp.get("result", {})
        server_info = result.get("serverInfo", {})

        # Send initialized notification
        self.send_notification("notifications/initialized", {})
        return result

    def list_tools(self) -> list:
        """Discover tools via MCP tools/list."""
        resp = self.send_request("tools/list", {})
        self.tools = resp.get("result", {}).get("tools", [])
        return self.tools

    def call_tool(self, tool_name: str, arguments: dict) -> dict:
        """Call an MCP tool and return the result."""
        resp = self.send_request("tools/call", {
            "name": tool_name,
            "arguments": arguments,
        }, timeout=120)
        return resp.get("result", {})

    def stop(self):
        if self.process:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass
        pass  # Silent cleanup

def _sanitize_schema(schema: dict):
    """Fix JSON Schema constructs that the Copilot server rejects.

    The Copilot server requires every property to have a plain ``"type": "string"``
    field.  This function recursively:
    - Converts array-typed ``"type"`` (e.g. ``["object", "null"]``) to a string.
    - Flattens ``anyOf``/``oneOf`` unions into a single ``type``.
    """
    # Handle anyOf / oneOf (e.g. {"anyOf": [{"type": "object"}, {"type": "null"}]})
    for keyword in ("anyOf", "oneOf"):
        variants = schema.get(keyword)
        if isinstance(variants, list):
            types = [v.get("type") for v in variants
                     if isinstance(v, dict) and v.get("type") and v.get("type") != "null"]
            extra = {}
            for v in variants:
                if isinstance(v, dict) and v.get("type") != "null":
                    extra = {k: val for k, val in v.items() if k != "type"}
                    break
            schema.pop(keyword)
            schema["type"] = types[0] if types else "string"
            schema.update(extra)

    # Handle array "type" (e.g. {"type": ["object", "null"]})
    if isinstance(schema.get("type"), list):
        types = [t for t in schema["type"] if t != "null"]
        schema["type"] = types[0] if types else "string"

    # Ensure "type" exists
    if "type" not in schema and "properties" not in schema:
        schema["type"] = "string"

    for prop in schema.get("properties", {}).values():
        if isinstance(prop, dict):
            _sanitize_schema(prop)
    for kw in ("items", "additionalProperties"):
        if isinstance(schema.get(kw), dict):
            _sanitize_schema(schema[kw])


class ClientMCPManager:
    """Manages multiple client-side MCP servers and bridges them to the Copilot agent."""

    def __init__(self):
        self.servers: dict[str, MCPServer] = {}
        # Maps prefixed tool name -> (server_name, original_tool_name)
        self._tool_map: dict[str, tuple[str, str]] = {}

    def add_servers(self, config: dict):
        """Add MCP servers from a config dict (same format as --mcp).

        Config format:
        {
            "server-name": {
                "command": "executable",
                "args": ["arg1", "arg2"],
                "env": {"KEY": "VALUE"}
            }
        }
        """
        for name, server_config in config.items():
            if "command" not in server_config:
                print(f"[client-mcp] Skipping '{name}': no 'command' (HTTP transport not supported client-side)")
                continue
            server = MCPServer(
                name=name,
                command=server_config["command"],
                args=server_config.get("args", []),
                env=server_config.get("env", {}),
            )
            self.servers[name] = server

    def start_all(self, on_progress=None):
        """Start all servers, initialize them, and discover tools.

        Args:
            on_progress: Optional callback ``(message: str) -> None`` called
                before each server starts.
        """
        for name, server in self.servers.items():
            try:
                if on_progress:
                    on_progress(f"Starting MCP: {name}...")
                server.start()
                time.sleep(0.5)
                server.initialize()
                time.sleep(0.5)
                server.list_tools()
                print(f"[client-mcp] {name}: {len(server.tools)} tools")
            except Exception as e:
                print(f"[client-mcp] {name}: failed ({e})")

        # Build tool map with prefixed names to avoid collisions
        self._tool_map.clear()
        for name, server in self.servers.items():
            for tool in server.tools:
                tool_name = tool.get("name", "")
                prefixed = f"mcp_{name}_{tool_name}"
                self._tool_map[prefixed] = (name, tool_name)

    def get_tool_schemas(self) -> list[dict]:
        """Convert discovered MCP tools into conversation/registerTools format.

        Returns schemas compatible with the Copilot server's tool registration.
        """
        schemas = []
        for name, server in self.servers.items():
            for tool in server.tools:
                tool_name = tool.get("name", "")
                prefixed = f"mcp_{name}_{tool_name}"
                input_schema = tool.get("inputSchema", {"type": "object", "properties": {}})
                # Ensure "required" is always present (server validation demands it)
                if "required" not in input_schema:
                    input_schema["required"] = []
                # Copilot server requires "type" to be a string, not an array.
                # Sanitize properties like {"type": ["object", "null"]} -> {"type": "object"}
                _sanitize_schema(input_schema)

                schema = {
                    "name": prefixed,
                    "description": f"[{name}] {tool.get('description', tool_name)}",
                    "inputSchema": input_schema,
                }
                schemas.append(schema)
        return schemas

    def is_mcp_tool(self, tool_name: str) -> bool:
        """Check if a tool name is a client-side MCP tool."""
        return tool_name in self._tool_map

    def call_tool(self, tool_name: str, arguments: dict) -> str:
        """Call a client-side MCP tool and return the result as text."""
        if tool_name not in self._tool_map:
            return f"Unknown MCP tool: {tool_name}"

        server_name, original_name = self._tool_map[tool_name]
        server = self.servers.get(server_name)
        if not server:
            return f"MCP server '{server_name}' not found"

        try:
            result = server.call_tool(original_name, arguments)
            # MCP tools/call returns {"content": [{"type": "text", "text": "..."}], ...}
            content = result.get("content", [])
            parts = []
            for item in content:
                if isinstance(item, dict):
                    text = item.get("text", item.get("value", ""))
                    if text:
                        parts.append(text)
                elif isinstance(item, str):
                    parts.append(item)
            return "\n".join(parts) if parts else json.dumps(result)
        except TimeoutError:
            return f"MCP tool '{original_name}' timed out"
        except Exception as e:
            return f"MCP tool '{original_name}' error: {e}"

    def stop_all(self):
        for server in self.servers.values():
            server.stop()