#!/usr/bin/env python3
"""
GitHub Copilot Language Server Client
Communicates with copilot-language-server via LSP JSON-RPC over stdio.
"""

import argparse
import base64
import glob
import json
import os
import readline
import shutil
import sys
import subprocess
import threading
import time
import uuid
from urllib.parse import urlparse

from copilot_cli.mcp import ClientMCPManager
from copilot_cli.lsp_bridge import LSPBridgeManager
from copilot_cli.platform_utils import path_to_file_uri, default_copilot_binary, default_apps_json
from copilot_cli.tools import TOOL_SCHEMAS, TOOL_EXECUTORS, BUILTIN_TOOL_NAMES, ToolContext

try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib  # pip install tomli (Python < 3.11 fallback)

CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.toml")

# Defaults (overridden by copilot_config.toml and CLI args)
COPILOT_BINARY = default_copilot_binary()
APPS_JSON = default_apps_json()

def _load_config() -> dict:
    """Load config from copilot_config.toml next to this script.
    Returns empty dict if file doesn't exist."""
    if os.path.isfile(CONFIG_FILE):
        with open(CONFIG_FILE, "rb") as f:
            return tomllib.load(f)
    return {}

def _get_config_value(config: dict, key: str, default=None):
    """Get a config value, expanding ~ and globs in paths."""
    val = config.get(key, default)
    if isinstance(val, str):
        val = os.path.expanduser(val)
        if "*" in val or "?" in val:
            matches = sorted(glob.glob(val))
            if matches:
                val = matches[-1]  # latest version (sorted alphabetically)
    return val

CONFIG = _load_config()

class CopilotClient:
    def __init__(self):
        self.process = None
        self.request_id = 0
        self._buffer = b""
        self._responses = {}
        self._notifications = []
        self._progress = {}  # workDoneToken -> list of progress updates
        self._pending_server_requests = []  # server->client requests to handle
        self._doc_versions = {}  # uri -> version counter
        self._feature_flags = {}  # populated from featureFlagsNotification
        self._lock = threading.Lock()
        self._reader_thread = None
        self.workspace_root = "/tmp/copilot-workspace"
        self.verbose = False
        self._spinner_clear = None  # callable set by cmd_chat to clear spinner
        self.client_mcp: ClientMCPManager | None = None  # Client-side MCP bridge
        self.lsp_bridge: LSPBridgeManager | None = None  # LSP bridge for code intelligence

    def _read_auth(self) -> dict:
        """Read OAuth token from apps.json."""
        apps_path = _get_config_value(CONFIG, "apps_json", APPS_JSON)
        with open(os.path.expanduser(apps_path), "r") as f:
            apps = json.load(f)
        # apps.json keys are like "github.com:AppId"
        # Prefer ghu_ tokens (GitHub user/Copilot tokens) over gho_ (OAuth app tokens)
        for key, value in apps.items():
            token = value.get("oauth_token", "")
            if token.startswith("ghu_"):
                # Extract app ID from key (e.g. "github.com:Iv23ct..." -> "Iv23ct...")
                app_id = key.split(":", 1)[1] if ":" in key else ""
                return {
                    "token": token,
                    "user": value.get("user", ""),
                    "app_id": app_id,
                }
        # Fallback to any token
        for key, value in apps.items():
            if "oauth_token" in value:
                app_id = key.split(":", 1)[1] if ":" in key else ""
                return {
                    "token": value["oauth_token"],
                    "user": value.get("user", ""),
                    "app_id": app_id,
                }
        raise RuntimeError("No OAuth token found in apps.json")

    def _next_id(self) -> int:
        self.request_id += 1
        return self.request_id

    def _encode_message(self, msg: dict) -> bytes:
        """Encode a JSON-RPC message with Content-Length header."""
        body = json.dumps(msg).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        return header + body

    def _read_message(self) -> dict | None:
        """Parse one LSP message from the buffer."""
        header_end = self._buffer.find(b"\r\n\r\n")
        if header_end == -1:
            return None

        header_section = self._buffer[:header_end].decode("ascii")
        content_length = None
        for line in header_section.split("\r\n"):
            if line.lower().startswith("content-length:"):
                content_length = int(line.split(":")[1].strip())
                break

        if content_length is None:
            return None

        body_start = header_end + 4
        body_end = body_start + content_length

        if len(self._buffer) < body_end:
            return None

        body = self._buffer[body_start:body_end]
        self._buffer = self._buffer[body_end:]

        return json.loads(body.decode("utf-8"))

    def _reader_loop(self):
        """Background thread: read stdout and parse messages."""
        while self.process and self.process.poll() is None:
            try:
                data = self.process.stdout.read1(4096)
            except OSError:
                break
            if not data:
                break
            with self._lock:
                self._buffer += data
                while True:
                    msg = self._read_message()
                    if msg is None:
                        break
                    msg_id = msg.get("id")
                    msg_method = msg.get("method")
                    if msg_id is not None and msg_method is None:
                        # Response to our request
                        self._responses[msg_id] = msg
                    elif msg_id is not None and msg_method is not None:
                        # Server->client request (has both id and method)
                        # Must handle outside the lock to avoid deadlock
                        self._pending_server_requests.append(msg)
                    elif msg.get("method") == "$/progress":
                        # Route progress updates by token
                        token = msg.get("params", {}).get("token")
                        if token is not None:
                            self._progress.setdefault(token, []).append(msg["params"].get("value", {}))
                    elif msg.get("method") == "copilot/mcpTools":
                        servers = msg.get("params", {}).get("servers", [])
                        for srv in servers:
                            name = srv.get("name", "?")
                            status = srv.get("status", "?")
                            tools = srv.get("tools", [])
                            print(f"[mcp] {name}: {status} ({len(tools)} tools)")
                    elif msg.get("method") == "featureFlagsNotification":
                        self._feature_flags = msg.get("params", {})
                        self._notifications.append(msg)
                    else:
                        self._notifications.append(msg)

    def start(self, proxy_url: str = None):
        """Spawn the copilot-language-server process."""
        self._auth = self._read_auth()
        env = os.environ.copy()
        # Don't set GITHUB_TOKEN - it bypasses apps.json token exchange
        # and creates a placeholder session missing org feature flags.

        if proxy_url:
            env["HTTP_PROXY"] = proxy_url
            env["HTTPS_PROXY"] = proxy_url

        binary = _get_config_value(CONFIG, "copilot_binary", COPILOT_BINARY)
        self.process = subprocess.Popen(
            [os.path.expanduser(binary), "--stdio"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        self._reader_thread = threading.Thread(target=self._reader_loop, daemon=True)
        self._reader_thread.start()

        # Process server->client requests
        def _request_handler():
            while self.process and self.process.poll() is None:
                reqs = []
                with self._lock:
                    reqs = self._pending_server_requests[:]
                    self._pending_server_requests.clear()
                for msg in reqs:
                    self._handle_server_request(msg)
                time.sleep(0.05)
        threading.Thread(target=_request_handler, daemon=True).start()

        # Drain stderr silently (suppress Node.js warnings)
        def _stderr_reader():
            while self.process and self.process.poll() is None:
                self.process.stderr.readline()
        threading.Thread(target=_stderr_reader, daemon=True).start()

    def send_request(self, method: str, params: dict = None, timeout: int = 120) -> dict:
        """Send a JSON-RPC request and wait for the response."""
        msg_id = self._next_id()
        msg = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            msg["params"] = params

        encoded = self._encode_message(msg)
        self.process.stdin.write(encoded)
        self.process.stdin.flush()
        start = time.time()
        while time.time() - start < timeout:
            with self._lock:
                if msg_id in self._responses:
                    return self._responses.pop(msg_id)
            time.sleep(0.05)

        raise TimeoutError(f"No response for request {msg_id} ({method})")

    def send_notification(self, method: str, params: dict = None):
        """Send a JSON-RPC notification (no response expected)."""
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params

        encoded = self._encode_message(msg)
        self.process.stdin.write(encoded)
        self.process.stdin.flush()

    def initialize(self, root_uri: str = "file:///tmp/copilot-workspace",
                   github_app_id: str = None, proxy_url: str = None):
        """Send LSP initialize request with auth in initializationOptions."""
        resp = self.send_request("initialize", {
            "processId": os.getpid(),
            "capabilities": {
                "textDocumentSync": {
                    "openClose": True,
                    "change": 1,  # Full sync
                    "save": True,
                },
                "workspace": {
                    "workspaceFolders": True,
                    "didChangeWatchedFiles": {
                        "dynamicRegistration": True,
                    },
                    "fileOperations": {
                        "didCreate": True,
                        "didRename": True,
                        "didDelete": True,
                    },
                },
            },
            "rootUri": root_uri,
            "workspaceFolders": [
                {"uri": root_uri, "name": os.path.basename(root_uri.rstrip("/"))},
            ],
            "clientInfo": {
                "name": "copilot-cli-client",
                "version": "0.1.0",
            },
            "initializationOptions": {
                "editorInfo": {
                    "name": "JetBrains-IC",
                    "version": "2025.2",
                },
                "editorPluginInfo": {
                    "name": "copilot-intellij",
                    "version": "1.420.0.nightly",
                },
                "editorConfiguration": {},
                "networkProxy": {"url": proxy_url} if proxy_url else {},
                "githubAppId": github_app_id or "Iv1.b507a08c87ecfe98",
            },
        })
        info = resp.get('result', {}).get('serverInfo', {})
        print(f"[*] Server: {info.get('name', '?')} v{info.get('version', '?')}")

        # Send initialized notification
        self.send_notification("initialized", {})
        return resp

    def set_editor_info(self, proxy_url: str = None):
        """Authenticate with Copilot using the OAuth token (legacy, still works)."""
        resp = self.send_request("setEditorInfo", {
            "editorInfo": {
                "name": "JetBrains-IC",
                "version": "2025.2",
            },
            "editorPluginInfo": {
                "name": "copilot-intellij",
                "version": "1.420.0",
            },
            "editorConfiguration": {},
            "networkProxy": {"url": proxy_url} if proxy_url else {},
        })
        print(f"[*] Editor info: {resp.get('result', 'error')}")
        return resp

    def sign_in_with_token(self):
        """Sign in using the OAuth token from apps.json."""
        auth = self._read_auth()
        # Try signInConfirm with the token directly
        resp = self.send_request("signInConfirm", {
            "userCode": auth["token"],
        })
        print(f"[*] signInConfirm response: {json.dumps(resp, indent=2)}")
        return resp

    @property
    def is_server_mcp_enabled(self) -> bool:
        """Check if server-side MCP is allowed (from org feature flags)."""
        return self._feature_flags.get("mcp", False) is True

    def check_status(self):
        """Check Copilot authentication status."""
        resp = self.send_request("checkStatus", {})
        result = resp.get("result", {})
        print(f"[*] Auth: {result.get('status', '?')} (user: {result.get('user', '?')})")
        return resp

    def open_document(self, uri: str, language_id: str, text: str, version: int = 1):
        """Notify the server that a document was opened."""
        self._doc_versions[uri] = version
        self.send_notification("textDocument/didOpen", {
            "textDocument": {
                "uri": uri,
                "languageId": language_id,
                "version": version,
                "text": text,
            }
        })

    def _sync_file_to_server(self, file_path: str, new_content: str):
        """Send textDocument/didChange to sync file content with the server.

        If the document was previously opened, sends didChange with incremented version.
        Otherwise, opens it as a new document.
        """
        uri = path_to_file_uri(file_path)

        if uri in self._doc_versions:
            # Increment version and send full-content change
            self._doc_versions[uri] += 1
            version = self._doc_versions[uri]
            self.send_notification("textDocument/didChange", {
                "textDocument": {
                    "uri": uri,
                    "version": version,
                },
                "contentChanges": [
                    {"text": new_content},  # Full document sync (textDocumentSync: 1)
                ],
            })
        else:
            # Guess language from extension
            ext_to_lang = {
                ".py": "python", ".js": "javascript", ".ts": "typescript",
                ".java": "java", ".rb": "ruby", ".go": "go", ".rs": "rust",
                ".c": "c", ".cpp": "cpp", ".h": "c", ".cs": "csharp",
                ".html": "html", ".css": "css", ".json": "json", ".md": "markdown",
                ".sh": "shellscript", ".yaml": "yaml", ".yml": "yaml",
                ".xml": "xml", ".sql": "sql", ".txt": "plaintext",
            }
            ext = os.path.splitext(file_path)[1].lower()
            lang = ext_to_lang.get(ext, "plaintext")
            self.open_document(uri, lang, new_content)

    def get_completions(self, uri: str, line: int, character: int) -> dict:
        """Request Copilot completions at a specific position."""
        resp = self.send_request("getCompletions", {
            "doc": {
                "uri": uri,
                "position": {
                    "line": line,
                    "character": character,
                },
                "insertSpaces": True,
                "tabSize": 4,
                "version": 1,
            },
        })
        return resp

    def get_completions_cycling(self, uri: str, line: int, character: int) -> dict:
        """Request multiple Copilot completions (cycling)."""
        resp = self.send_request("getCompletionsCycling", {
            "doc": {
                "uri": uri,
                "position": {
                    "line": line,
                    "character": character,
                },
                "insertSpaces": True,
                "tabSize": 4,
                "version": 1,
            },
        })
        return resp

    def conversation_create(self, message: str, model: str = None,
                            agent_mode: bool = False, workspace_folder: str = None,
                            on_progress: callable = None) -> dict:
        """Create a new conversation (Copilot Chat) with an initial turn.

        Args:
            message: The chat prompt.
            model: Model ID (e.g. "gpt-4.1", "claude-sonnet-4").
            agent_mode: If True, use Agent mode (tools, file edits, etc.).
            workspace_folder: Workspace root URI for agent mode context.
            on_progress: Optional streaming callback ``(kind, data) -> None``.
        """
        work_done_token = f"copilot-chat-{uuid.uuid4().hex[:8]}"
        params = {
            "workDoneToken": work_done_token,
            "turns": [
                {"request": message}
            ],
            "capabilities": {
                "allSkills": agent_mode,
            },
            "source": "panel",
        }
        if agent_mode:
            params["chatMode"] = "Agent"
            params["needToolCallConfirmation"] = True
        if model:
            params["model"] = model
        if workspace_folder:
            params["workspaceFolder"] = workspace_folder
            params["workspaceFolders"] = [{"uri": workspace_folder, "name": os.path.basename(workspace_folder)}]

        resp = self.send_request("conversation/create", params, timeout=300)
        result = resp.get("result", {})
        if isinstance(result, list):
            result = result[0] if result else {}
        conversation_id = result.get("conversationId", "")
        model_name = result.get("modelName", "unknown")
        if self._spinner_clear:
            self._spinner_clear()
        if self.verbose:
            print(f"\033[90m⏺ conversation created (model: {model_name})\033[0m")

        # Collect streamed response from progress notifications
        reply_data = self._collect_chat_reply(work_done_token, timeout=300 if agent_mode else 60,
                                              on_progress=on_progress)
        return {"conversationId": conversation_id, "reply": reply_data["text"],
                "agent_rounds": reply_data.get("agent_rounds", []), "raw": resp}

    def conversation_turn(self, conversation_id: str, message: str,
                          model: str = None, agent_mode: bool = False,
                          workspace_folder: str = None,
                          on_progress: callable = None) -> dict:
        """Send a follow-up message in an existing conversation.

        Args:
            workspace_folder: Workspace root URI for agent mode context.
            on_progress: Optional streaming callback ``(kind, data) -> None``.
        """
        work_done_token = f"copilot-chat-{uuid.uuid4().hex[:8]}"
        params = {
            "workDoneToken": work_done_token,
            "conversationId": conversation_id,
            "message": message,
            "source": "panel",
        }
        if agent_mode:
            params["chatMode"] = "Agent"
            params["needToolCallConfirmation"] = True
        if workspace_folder:
            params["workspaceFolder"] = workspace_folder
            params["workspaceFolders"] = [{"uri": workspace_folder, "name": os.path.basename(workspace_folder)}]
        if model:
            params["model"] = model

        resp = self.send_request("conversation/turn", params, timeout=300)
        reply_data = self._collect_chat_reply(work_done_token, timeout=300 if agent_mode else 60,
                                              on_progress=on_progress)
        return {"reply": reply_data["text"], "agent_rounds": reply_data.get("agent_rounds", []), "raw": resp}

    def _send_response(self, req_id, result):
        """Send a JSON-RPC response to a server request."""
        reply = {"jsonrpc": "2.0", "id": req_id, "result": result}
        encoded = self._encode_message(reply)
        self.process.stdin.write(encoded)
        self.process.stdin.flush()

    def _handle_server_request(self, msg: dict):
        """Handle a server->client request (has both id and method)."""
        req_id = msg["id"]
        method = msg.get("method", "")
        params = msg.get("params", {})

        if method == "conversation/invokeClientToolConfirmation":
            self._send_response(req_id, [{"result": "accept"}, None])

        elif method == "conversation/invokeClientTool":
            tool_name = params.get("name", params.get("toolName", "unknown"))
            tool_input = params.get("input", params.get("arguments", {}))
            if self._spinner_clear:
                self._spinner_clear()
            print(f"\033[32m⏺\033[0m \033[1m{tool_name}\033[0m\033[37m({json.dumps(tool_input)[:150]})\033[0m")
            result = self._execute_client_tool(tool_name, tool_input)
            # Client-side MCP tools already return the tuple format.
            # All other tools (including former "built-ins") are registered
            # as client tools and need the tuple wrapping.
            is_client_mcp = self.client_mcp and self.client_mcp.is_mcp_tool(tool_name)
            if not is_client_mcp:
                result = self._wrap_registered_tool_result(result)
            self._send_response(req_id, result)

        elif method == "copilot/watchedFiles":
            self._send_response(req_id, {"watchedFiles": []})

        elif method == "window/showMessageRequest":
            print(f"[server] showMessage: {params.get('message', '')[:200]}")
            self._send_response(req_id, None)

        else:
            print(f"[server request] {method} (id={req_id}) - auto-responding")
            self._send_response(req_id, None)

    def _execute_client_tool(self, tool_name: str, tool_input: dict):
        """Execute a client-side tool and return the result."""
        # Check client-side MCP tools first
        if self.client_mcp and self.client_mcp.is_mcp_tool(tool_name):
            result_text = self.client_mcp.call_tool(tool_name, tool_input)
            if self.verbose:
                for line in result_text[:200].splitlines():
                    print(f"\033[90m  ⎿  {line}\033[0m")
            return [{"content": [{"value": result_text}], "status": "success"}, None]

        executor = TOOL_EXECUTORS.get(tool_name)
        if not executor:
            print(f"\033[31m  ⎿  Unknown tool: {tool_name}\033[0m")
            return [{"type": "text", "value": f"Error: Unknown tool: {tool_name}"}]
        ctx = ToolContext(
            workspace_root=self.workspace_root,
            sync_file_to_server=self._sync_file_to_server,
            open_document=self.open_document,
            lsp_bridge=self.lsp_bridge,
        )
        try:
            result = executor(tool_input, ctx)
        except (OSError, subprocess.SubprocessError, json.JSONDecodeError,
                ValueError, TypeError) as e:
            print(f"\033[31m  ⎿  Error: {e}\033[0m")
            return [{"type": "text", "value": f"Error: {e}"}]

        return result

    @staticmethod
    def _wrap_registered_tool_result(result) -> list:
        """Convert a tool result into the tuple format the server expects:
        [{"content": [{"value": "text"}, ...], "status": "success"}, null].
        The server destructures as [resultObj, error] and validates resultObj."""
        if isinstance(result, list):
            # [{"type": "text", "value": "..."}] -> content array
            content = [{"value": item.get("value", str(item))} for item in result]
            return [{"content": content, "status": "success"}, None]
        elif isinstance(result, dict):
            status = "error" if result.get("result") == "error" else "success"
            text = result.get("output", result.get("message", json.dumps(result)))
            return [{"content": [{"value": text}], "status": status}, None]
        return [{"content": [{"value": str(result)}], "status": "success"}, None]

    def configure_proxy(self, proxy_url: str, no_ssl_verify: bool = False):
        """Send proxy configuration to the language server.

        The server reads settings.http.proxy, proxyStrictSSL, proxyAuthorization
        via workspace/didChangeConfiguration → applyHttpConfiguration.
        """
        parsed = urlparse(proxy_url)
        http_settings = {"proxy": proxy_url, "proxyStrictSSL": not no_ssl_verify}

        # If proxy URL has credentials (http://user:pass@host:port), build
        # a Basic auth header so the server can use it for proxy auth.
        if parsed.username:
            creds = f"{parsed.username}:{parsed.password or ''}"
            auth_header = f"Basic {base64.b64encode(creds.encode()).decode()}"
            http_settings["proxyAuthorization"] = auth_header
            # Send a clean URL (without credentials) as the proxy address
            clean = parsed._replace(netloc=f"{parsed.hostname}:{parsed.port}" if parsed.port
            else parsed.hostname)
            http_settings["proxy"] = clean.geturl()

        self.send_notification("workspace/didChangeConfiguration", {
            "settings": {"http": http_settings}
        })
        print(f"[*] Proxy configured: {http_settings['proxy']}"
              f"{' (auth)' if 'proxyAuthorization' in http_settings else ''}"
              f"{' (no-ssl-verify)' if no_ssl_verify else ''}")

    def configure_mcp(self, mcp_config: dict):
        """Send MCP server configuration to the language server.

        The server expects the config as a JSON string inside
        workspace/didChangeConfiguration → settings.github.copilot.mcp.
        """
        self.send_notification("workspace/didChangeConfiguration", {
            "settings": {
                "github": {
                    "copilot": {
                        "mcp": json.dumps(mcp_config)
                    }
                }
            }
        })
        print(f"[*] Sent MCP config ({len(mcp_config)} server(s)): {', '.join(mcp_config.keys())}")

    def mcp_list_servers(self) -> list:
        """List registered MCP servers (derived from mcp/getTools)."""
        resp = self.mcp_get_tools()
        servers = resp.get("result", [])
        if not isinstance(servers, list):
            return []
        return [{"name": s.get("name"), "status": s.get("status"),
                 "prefix": s.get("prefix"), "tool_count": len(s.get("tools", []))}
                for s in servers]

    def mcp_get_tools(self) -> dict:
        """Get tools from all connected MCP servers."""
        return self.send_request("mcp/getTools", {})

    def mcp_server_action(self, server_name: str, action: str) -> dict:
        """Perform an action on an MCP server (start, stop, restart, logout, clearOAuth)."""
        return self.send_request("mcp/serverAction", {
            "serverName": server_name,
            "action": action,
        })

    def register_client_tools(self):
        """Register all client-side tools that the agent can invoke.

        Registers ALL tools (including the 15 that the server knows as
        'shared' built-ins) because the server only makes tools available
        to the model once the client registers them.  Also registers
        client-side MCP tools if any are configured.
        """
        all_tools = list(TOOL_SCHEMAS.values())

        # Add client-side MCP tools
        mcp_tools = []
        if self.client_mcp:
            mcp_tools = self.client_mcp.get_tool_schemas()
            all_tools.extend(mcp_tools)

        resp = self.send_request("conversation/registerTools", {"tools": all_tools})
        if "error" in resp:
            print(f"[!] Tool registration error: {json.dumps(resp['error'])[:300]}")
        else:
            mcp_note = f" + {len(mcp_tools)} client-mcp" if mcp_tools else ""
            print(f"[*] Registered {len(all_tools)} client tools{mcp_note}")
        return resp

    def conversation_destroy(self, conversation_id: str) -> dict:
        """Destroy/close a conversation."""
        return self.send_request("conversation/destroy", {
            "conversationId": conversation_id,
        })

    def _collect_chat_reply(self, work_done_token: str, timeout: float = 60,
                            on_progress: callable = None) -> dict:
        """Collect streamed chat reply from $/progress notifications.

        Args:
            work_done_token: Token to match progress notifications.
            timeout: Max seconds to wait for response.
            on_progress: Optional callback ``(kind, data) -> None`` invoked on
                each progress update.  ``kind`` is one of ``"delta"``,
                ``"agent_round"``, ``"annotation"``, ``"reference"``,
                ``"done"``.

        Returns dict with:
            text: The full reply text
            agent_rounds: List of agent round dicts (for agent mode)
        """
        reply_parts = []
        agent_rounds = []
        start = time.time()
        last_activity = start
        inactivity_limit = 60  # seconds with no updates before giving up
        done = False

        while time.time() - start < timeout and not done:
            time.sleep(0.1)
            with self._lock:
                updates = self._progress.pop(work_done_token, [])

            if updates:
                last_activity = time.time()

            for update in updates:
                kind = update.get("kind")
                if kind == "end":
                    done = True
                    if on_progress:
                        on_progress("done", {"text": "".join(reply_parts)})
                    break
                # Progress report - look for reply content
                reply = update.get("reply")
                if reply:
                    reply_parts.append(reply)
                    if on_progress:
                        on_progress("delta", {"delta": reply})
                # Some versions send delta text
                delta = update.get("delta")
                if delta:
                    reply_parts.append(delta)
                    if on_progress:
                        on_progress("delta", {"delta": delta})
                # Also check message field
                message = update.get("message")
                if message and isinstance(message, str) and kind != "begin":
                    reply_parts.append(message)
                    if on_progress:
                        on_progress("delta", {"delta": message})
                # Agent mode: editAgentRounds contain tool calls and results
                rounds = update.get("editAgentRounds", [])
                for r in rounds:
                    agent_rounds.append(r)
                    round_reply = r.get("reply", "")
                    if round_reply:
                        reply_parts.append(round_reply)
                    if on_progress:
                        on_progress("agent_round", r)
                    # Tool call progress is already logged by _handle_server_request
                # Agent mode: annotations (file edits, etc.)
                annotations = update.get("annotations", [])
                for ann in annotations:
                    print(f"\033[90m  ⎿  annotation: {json.dumps(ann)[:200]}\033[0m")
                    if on_progress:
                        on_progress("annotation", ann)
                # Agent mode: references (files being read)
                references = update.get("references", [])
                for ref in references:
                    status = ref.get("status", "")
                    uri = ref.get("uri", "")
                    if uri:
                        print(f"\033[90m  ⎿  {status}: {uri}\033[0m")
                    if on_progress:
                        on_progress("reference", ref)

            # Fail fast if no activity for inactivity_limit seconds
            if not done and time.time() - last_activity > inactivity_limit:
                raise TimeoutError(
                    f"No response from Copilot for {inactivity_limit}s. "
                    "Check your network/proxy settings."
                )

        if not done:
            raise TimeoutError(
                f"Chat response timed out after {timeout}s. "
                "Check your network/proxy settings."
            )

        return {"text": "".join(reply_parts), "agent_rounds": agent_rounds}

    def list_models(self) -> dict:
        """List available Copilot models."""
        resp = self.send_request("copilot/models", {})
        return resp

    def get_mcp_status(self) -> list:
        """Return status of all MCP servers (server-side + client-side)."""
        servers = []
        try:
            servers.extend(self.mcp_list_servers())
        except Exception:
            pass
        if self.client_mcp:
            for name, srv in self.client_mcp.servers.items():
                servers.append({
                    "name": name,
                    "status": "running" if srv.process and srv.process.poll() is None else "stopped",
                    "prefix": f"mcp_{name}",
                    "tool_count": len(srv.tools) if hasattr(srv, "tools") else 0,
                    "source": "client",
                })
        return servers

    def stop(self):
        """Shutdown the language server, MCP servers, and LSP bridge."""
        if self.lsp_bridge:
            self.lsp_bridge.stop_all()
        if self.client_mcp:
            self.client_mcp.stop_all()
        try:
            self.send_request("shutdown", {})
            self.send_notification("exit", {})
        except Exception:
            pass
        if self.process:
            self.process.terminate()
            self.process.wait(timeout=5)
        print("[*] Server stopped.")

def _load_mcp_config(mcp_arg: str | None) -> dict | None:
    """Load MCP config from a file path or inline JSON string."""
    if not mcp_arg:
        return None
    # Try as file path first
    if os.path.isfile(mcp_arg):
        with open(mcp_arg, "r") as f:
            return json.load(f)
    # Try as inline JSON
    try:
        config = json.loads(mcp_arg)
        if isinstance(config, dict):
            return config
    except json.JSONDecodeError:
        pass
    raise ValueError(f"--mcp: not a valid file path or JSON string: {mcp_arg}")

class SessionPool:
    """Process-wide pool of shared CopilotClient instances.

    Caches a single ``CopilotClient`` (and its ``copilot-language-server``
    process) per workspace path so that multiple agents/conversations can
    share the same LSP connection instead of each spawning their own.

    Usage::

        client = _init_client(workspace, shared=True, ...)
        # ... use client normally ...
        release_client(client)  # instead of client.stop()
    """

    _instance: "SessionPool | None" = None
    _instance_lock = threading.Lock()

    @classmethod
    def get(cls) -> "SessionPool":
        with cls._instance_lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    @classmethod
    def reset(cls):
        """Shut down all pooled clients and clear the singleton (for tests)."""
        with cls._instance_lock:
            if cls._instance is not None:
                for key in list(cls._instance._clients):
                    try:
                        cls._instance._clients[key].stop()
                    except Exception:
                        pass
                cls._instance = None

    def __init__(self):
        self._clients: dict[str, CopilotClient] = {}  # workspace -> client
        self._refcounts: dict[str, int] = {}
        self._lock = threading.Lock()

    def acquire(self, workspace: str, agent_mode: bool = False,
                mcp_config: dict = None,
                lsp_config: dict = None,
                proxy_url: str = None, no_ssl_verify: bool = False,
                verbose: bool = False,
                on_progress: callable = None) -> CopilotClient:
        """Return a (possibly cached) CopilotClient for *workspace*.

        The first call for a given workspace performs the full
        ``_init_client`` startup sequence.  Subsequent calls return the
        existing client, escalating capabilities (agent_mode, MCP) if the
        new caller requests them.
        """
        key = os.path.abspath(workspace)
        with self._lock:
            if key in self._clients:
                client = self._clients[key]
                self._refcounts[key] += 1

                # Escalate to agent_mode if a new caller needs it
                if agent_mode and not getattr(client, "_pool_agent_mode", False):
                    client.register_client_tools()
                    _open_workspace_files(client, key)
                    client._pool_agent_mode = True

                return client

        # First acquisition — full init (outside the lock to avoid blocking)
        client = _init_client_internal(
            workspace, agent_mode=agent_mode, mcp_config=mcp_config,
            lsp_config=lsp_config,
            proxy_url=proxy_url, no_ssl_verify=no_ssl_verify,
            verbose=verbose, on_progress=on_progress,
        )
        client._pool_agent_mode = agent_mode

        with self._lock:
            # Double-check: another thread may have raced us
            if key in self._clients:
                # Someone beat us — stop ours and use theirs
                client.stop()
                client = self._clients[key]
                self._refcounts[key] += 1
            else:
                self._clients[key] = client
                self._refcounts[key] = 1

        return client

    def release(self, client: CopilotClient):
        """Decrement refcount; stop the client when it hits zero."""
        key = client.workspace_root
        with self._lock:
            if key not in self._refcounts:
                # Not pooled — stop directly
                client.stop()
                return
            self._refcounts[key] -= 1
            if self._refcounts[key] <= 0:
                del self._clients[key]
                del self._refcounts[key]
                client.stop()


def release_client(client: CopilotClient):
    """Release a client that was created with ``shared=True``.

    If the client is pooled, its refcount is decremented and the
    underlying ``copilot-language-server`` process is only stopped when
    the last reference is released.  For non-pooled clients this is
    equivalent to ``client.stop()``.
    """
    pool = SessionPool.get()
    pool.release(client)


def _open_workspace_files(client: CopilotClient, workspace: str):
    """Walk *workspace* and open known file types as LSP documents."""
    doc_count = 0
    lang_map = {
        ".py": "python", ".js": "javascript", ".ts": "typescript",
        ".java": "java", ".rb": "ruby", ".go": "go", ".rs": "rust",
        ".c": "c", ".cpp": "cpp", ".h": "c", ".cs": "csharp",
        ".html": "html", ".css": "css", ".json": "json", ".md": "markdown",
        ".sh": "shellscript", ".yaml": "yaml", ".yml": "yaml",
    }
    for root, _, files in os.walk(workspace):
        for fname in files:
            fpath = os.path.join(root, fname)
            ext = os.path.splitext(fname)[1].lower()
            if ext in lang_map:
                uri = path_to_file_uri(fpath)
                with open(fpath, "r", errors="replace") as f:
                    client.open_document(uri, lang_map[ext], f.read())
                doc_count += 1
    if doc_count:
        print(f"[*] Opened {doc_count} workspace files")


def _init_client_internal(workspace: str, agent_mode: bool = False,
                          mcp_config: dict = None,
                          lsp_config: dict = None,
                          proxy_url: str = None, no_ssl_verify: bool = False,
                          verbose: bool = False,
                          on_progress: callable = None) -> CopilotClient:
    """Core init logic — always creates a fresh CopilotClient.

    Callers should prefer ``_init_client()`` which supports the ``shared``
    parameter for session pooling.
    """
    def _emit(msg):
        if on_progress:
            on_progress(msg)

    client = CopilotClient()
    client.workspace_root = os.path.abspath(workspace)
    client.verbose = verbose
    _emit("Starting Copilot LSP...")
    client.start(proxy_url=proxy_url)
    client.initialize(root_uri=path_to_file_uri(client.workspace_root),
                      github_app_id=client._auth.get("app_id"),
                      proxy_url=proxy_url)
    time.sleep(1)
    _emit("Authenticating...")
    client.set_editor_info(proxy_url=proxy_url)
    time.sleep(0.5)
    if proxy_url:
        client.configure_proxy(proxy_url, no_ssl_verify=no_ssl_verify)
    client.check_status()
    time.sleep(0.5)

    if mcp_config:
        for srv_cfg in mcp_config.values():
            if "args" in srv_cfg:
                srv_cfg["args"] = [a.replace("{workspace}", client.workspace_root)
                                   for a in srv_cfg["args"]]
            if "url" in srv_cfg:
                srv_cfg["url"] = srv_cfg["url"].replace("{workspace}", client.workspace_root)

    if mcp_config:
        _emit("Starting MCP servers...")
        time.sleep(0.5)
        # Split config: url-based (SSE) servers must always run client-side
        # because the Copilot language server only supports command-based
        # (stdio) MCP servers.
        sse_servers = {n: c for n, c in mcp_config.items() if "url" in c}
        stdio_servers = {n: c for n, c in mcp_config.items() if "url" not in c}

        if stdio_servers and client.is_server_mcp_enabled:
            print(f"[*] MCP: server-side: {', '.join(stdio_servers.keys())}")
            client.configure_mcp(stdio_servers)
            time.sleep(4)
        elif stdio_servers:
            sse_servers.update(stdio_servers)

        if sse_servers:
            print(f"[*] MCP: client-side: {', '.join(sse_servers.keys())}")
            manager = ClientMCPManager(workspace_root=client.workspace_root)
            manager.add_servers(sse_servers)
            manager.start_all(on_progress=on_progress)
            client.client_mcp = manager

    effective_lsp = lsp_config if lsp_config is not None else CONFIG.get("lsp", {})
    client.lsp_bridge = LSPBridgeManager(client.workspace_root, effective_lsp)

    if effective_lsp:
        langs = []
        for lang, cfg in effective_lsp.items():
            cmd = cfg.get("command", "")
            args = cfg.get("args", [])
            if cmd == "npx" and args:
                pkg = next((a for a in args if not a.startswith("-")), cmd)
                langs.append(f"{lang} ({pkg})")
            else:
                langs.append(f"{lang} ({cmd})")
        print(f"[*] LSP: {', '.join(langs)}")

    if agent_mode:
        _emit("Registering tools...")
        client.register_client_tools()
        time.sleep(0.5)
        _emit("Opening workspace files...")
        _open_workspace_files(client, client.workspace_root)
    _emit("Ready")
    return client


def _init_client(workspace: str, agent_mode: bool = False,
                 mcp_config: dict = None,
                 lsp_config: dict = None,
                 proxy_url: str = None, no_ssl_verify: bool = False,
                 verbose: bool = False,
                 on_progress: callable = None,
                 shared: bool = False) -> CopilotClient:
    """Start and initialize a CopilotClient.

    Args:
        mcp_config: MCP server config. Automatically routed to server-side
            (if org allows MCP) or client-side (if org blocks MCP).
        lsp_config: LSP server config. If None, falls back to the
            ``[lsp]`` section of the main config.toml.
        on_progress: Optional callback ``(message: str) -> None`` for
            startup progress reporting (used by Agent Builder SSE).
        shared: If True, return a pooled client shared across callers
            for the same workspace. Use ``release_client()`` instead of
            ``client.stop()`` when done.
    """
    if shared:
        return SessionPool.get().acquire(
            workspace, agent_mode=agent_mode, mcp_config=mcp_config,
            lsp_config=lsp_config,
            proxy_url=proxy_url, no_ssl_verify=no_ssl_verify,
            verbose=verbose, on_progress=on_progress,
        )
    return _init_client_internal(
        workspace, agent_mode=agent_mode, mcp_config=mcp_config,
        lsp_config=lsp_config,
        proxy_url=proxy_url, no_ssl_verify=no_ssl_verify,
        verbose=verbose, on_progress=on_progress,
    )

def _common_kwargs(args) -> dict:
    """Extract common keyword args from parsed CLI args."""
    return {
        "proxy_url": getattr(args, "proxy", None),
        "no_ssl_verify": getattr(args, "no_ssl_verify", False),
        "verbose": getattr(args, "verbose", False),
    }

def cmd_mcp(args):
    """MCP server management subcommand."""
    mcp_config = _load_mcp_config(getattr(args, "mcp", None))
    client = _init_client(args.workspace, mcp_config=mcp_config, **_common_kwargs(args))
    try:
        action = args.mcp_action
        if action == "list":
            servers = client.mcp_list_servers()
            for s in servers:
                print(f"  {s['name']:20s}  status={s['status']}  tools={s['tool_count']}")
            if not servers:
                print("  (no MCP servers)")
        elif action == "tools":
            resp = client.mcp_get_tools()
            servers = resp.get("result", [])
            if isinstance(servers, list):
                for srv in servers:
                    srv_name = srv.get("name", "?")
                    status = srv.get("status", "?")
                    tools = srv.get("tools", [])
                    print(f"  [{srv_name}] ({status}) - {len(tools)} tool(s):")
                    for t in tools:
                        print(f"    {t.get('name', '?'):30s}  {t.get('description', '')[:60]}")
            else:
                print(json.dumps(resp, indent=2))
        elif action in ("start", "stop", "restart"):
            if not args.server_name:
                print("[!] Server name required for start/stop/restart")
                return
            resp = client.mcp_server_action(args.server_name, action)
            print(json.dumps(resp.get("result", resp), indent=2))
        else:
            print(f"[!] Unknown mcp action: {action}")
    finally:
        release_client(client)

def cmd_models(args):
    """List available Copilot models."""
    mcp_config = _load_mcp_config(getattr(args, "mcp", None))
    client = _init_client(args.workspace, mcp_config=mcp_config, **_common_kwargs(args))
    try:
        resp = client.list_models()
        models = resp.get("result", [])
        if isinstance(models, list):
            for m in models:
                mid = m.get("id", m.get("modelId", "?"))
                name = m.get("name", m.get("modelName", "?"))
                print(f"  {mid:30s}  {name}")
        else:
            print(json.dumps(resp, indent=2))
    finally:
        release_client(client)

def cmd_complete(args):
    """Request inline completions for a file at a given position."""
    file_path = os.path.abspath(args.file)
    with open(file_path, "r") as f:
        text = f.read()

    ext = os.path.splitext(file_path)[1].lower()
    lang_map = {".py": "python", ".js": "javascript", ".ts": "typescript", ".go": "go",
                ".rs": "rust", ".java": "java", ".c": "c", ".cpp": "cpp", ".rb": "ruby"}
    lang = lang_map.get(ext, "plaintext")

    client = _init_client(args.workspace, **_common_kwargs(args))
    try:
        uri = path_to_file_uri(file_path)
        client.open_document(uri, lang, text)
        time.sleep(0.5)

        # Default to end of file
        lines = text.split("\n")
        line = args.line if args.line is not None else len(lines) - 1
        char = args.character if args.character is not None else len(lines[line]) if line < len(lines) else 0

        print(f"[*] Requesting completions at {line}:{char}...")
        result = client.get_completions(uri, line=line, character=char)
        completions = result.get("result", {}).get("completions", [])
        if completions:
            for i, comp in enumerate(completions):
                print(f"\n--- Completion {i + 1} ---")
                print(comp.get("displayText", comp.get("text", "(empty)")))
        else:
            print("[!] No completions returned.")
    finally:
        release_client(client)

def _print_banner(workspace: str, model: str, agent_mode: bool, tool_count: int,
                  mcp_count: int):
    """Print a welcome banner with session info."""
    mode = "Agent" if agent_mode else "Chat"
    model_name = model or "default"
    ws_display = os.path.basename(workspace) or workspace

    # Build info line
    parts = [mode, model_name]
    if agent_mode:
        parts.append(f"{tool_count} tools")
        if mcp_count:
            parts.append(f"{mcp_count} mcp")

    info = " \033[90m·\033[0m ".join(parts)
    ws_line = f"\033[90m  {ws_display}\033[0m"

    print()
    print(f"  \033[94m╭─ Copilot CLI\033[0m")
    print(f"  \033[94m│\033[0m  {info}")
    print(f"  \033[94m│\033[0m {ws_line}")
    print(f"  \033[94m╰─\033[0m")
    print()


def cmd_chat(args):
    """Interactive chat session."""
    agent_mode = args.agent
    workspace = os.path.abspath(args.workspace)
    mcp_config = _load_mcp_config(getattr(args, "mcp", None))
    client = _init_client(workspace, agent_mode=agent_mode, mcp_config=mcp_config,
                          **_common_kwargs(args))

    try:
        workspace_uri = path_to_file_uri(workspace)
        conversation_id = None
        model = args.model

        # Print welcome banner
        tool_count = len(TOOL_SCHEMAS) + 15  # registered + built-in
        mcp_count = 0
        if client.client_mcp:
            mcp_count = sum(len(s.tools) for s in client.client_mcp.servers.values())
            tool_count += mcp_count
        _print_banner(workspace, model, agent_mode, tool_count, mcp_count)

        # If a prompt was passed on the command line, use it
        prompt = " ".join(args.prompt) if args.prompt else None

        while True:
            if prompt is None:
                try:
                    # Get terminal width for separator lines
                    try:
                        cols = os.get_terminal_size().columns
                    except OSError:
                        cols = 80
                    sep = "\033[90m" + "─" * cols + "\033[0m"
                    print(sep)
                    sys.stdout.write("\033[1m❯\033[0m ")
                    sys.stdout.flush()
                    prompt = input().strip()
                    print(sep)
                    # Replace separator + prompt + separator with styled prompt
                    print("\033[A\033[2K" * 3, end="")
                    print(f"\033[100m\033[97m ❯ {prompt} \033[0m")
                except (EOFError, KeyboardInterrupt):
                    print()
                    break
            if not prompt:
                prompt = None
                continue
            if prompt.lower() in ("exit", "quit", "/quit", "/exit"):
                break

            # Spinner while waiting for first token
            spinner_stop = threading.Event()
            streaming_started = threading.Event()
            def _clear_spinner():
                """Clear the spinner line — safe to call multiple times."""
                if not spinner_stop.is_set():
                    spinner_stop.set()
                    print("\r\033[K", flush=True)  # clear line and newline

            client._spinner_clear = _clear_spinner

            def _spinner():
                chars = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
                i = 0
                while not spinner_stop.is_set():
                    print(f"\r\033[33m{chars[i % len(chars)]} thinking...\033[0m", end="", flush=True)
                    i += 1
                    spinner_stop.wait(0.1)

            def _on_progress(kind, data):
                _clear_spinner()
                if kind == "delta":
                    delta = data.get("delta", "")
                    if delta:
                        if not streaming_started.is_set():
                            streaming_started.set()
                            print(f"\033[94m⏺\033[0m ", end="", flush=True)
                        print(delta, end="", flush=True)
                elif kind == "done":
                    if streaming_started.is_set():
                        print()  # final newline after streamed response

            spinner_thread = threading.Thread(target=_spinner, daemon=True)
            spinner_thread.start()

            if conversation_id is None:
                result = client.conversation_create(
                    prompt, model=model, agent_mode=agent_mode,
                    workspace_folder=workspace_uri if agent_mode else None,
                    on_progress=_on_progress,
                )
                conversation_id = result["conversationId"]
            else:
                result = client.conversation_turn(
                    conversation_id, prompt, model=model, agent_mode=agent_mode,
                    workspace_folder=workspace_uri if agent_mode else None,
                    on_progress=_on_progress,
                )

            _clear_spinner()
            client._spinner_clear = None
            spinner_thread.join(timeout=1)

            # If streaming didn't fire (e.g. empty reply), print the full reply
            if not streaming_started.is_set() and result["reply"]:
                print(f"\033[94m⏺\033[0m {result['reply']}")

            print()  # blank line before next prompt

            # One-shot mode: if prompt came from argv, exit after first reply
            if args.prompt:
                break
            prompt = None

        if conversation_id:
            client.conversation_destroy(conversation_id)
    finally:
        release_client(client)

def _parse_worker_defs(worker_defs: list[dict], default_model: str | None = None):
    """Convert raw worker dicts into WorkerConfig objects."""
    from copilot_cli.orchestrator import WorkerConfig
    return [
        WorkerConfig(
            role=w["role"],
            system_prompt=w.get("system_prompt", ""),
            model=w.get("model", default_model),
            tools_enabled=w.get("tools_enabled", "__ALL__"),
            agent_mode=w.get("agent_mode", True),
            workspace_root=w.get("workspace_root"),
            proxy_url=w.get("proxy_url"),
            no_ssl_verify=w.get("no_ssl_verify"),
            mcp_servers=w.get("mcp_servers"),
            lsp_servers=w.get("lsp_servers"),
            question_schema=w.get("question_schema"),
            answer_schema=w.get("answer_schema"),
        )
        for w in worker_defs
    ]


def load_orchestrator_config(path: str) -> dict:
    """Load an orchestrator config file (TOML or JSON).

    Returns a dict with keys: ``workers``, ``model``, ``transport``,
    ``system_prompt``, and any other top-level fields from the file.
    """
    ext = os.path.splitext(path)[1].lower()
    with open(path, "rb") as f:
        if ext == ".toml":
            config = tomllib.load(f)
        else:
            config = json.load(f)

    # Normalise [[workers]] (TOML array-of-tables) or "workers" (JSON array)
    raw_workers = config.get("workers", [])
    if isinstance(raw_workers, dict):
        # Single worker defined as [workers] instead of [[workers]]
        raw_workers = [raw_workers]
    config["_worker_defs"] = raw_workers
    return config


def cmd_orchestrate(args):
    """Multi-agent orchestrator mode."""
    from copilot_cli.orchestrator import run_orchestrator_cli

    workspace = os.path.abspath(args.workspace)
    goal = " ".join(args.prompt)
    model = args.model
    transport = getattr(args, "transport", None) or "mcp"
    workers = None

    # Load unified config file if provided
    config_path = getattr(args, "config", None)
    if config_path:
        config = load_orchestrator_config(config_path)
        # Config file provides defaults; CLI flags override
        if not model:
            model = config.get("model")
        if getattr(args, "transport", None) is None:
            transport = config.get("transport", "mcp")
        if config.get("_worker_defs"):
            workers = _parse_worker_defs(config["_worker_defs"], default_model=model)

    # --workers flag overrides config file workers
    workers_arg = getattr(args, "workers", None)
    if workers_arg:
        if os.path.isfile(workers_arg):
            with open(workers_arg, "r") as f:
                worker_defs = json.load(f)
        else:
            worker_defs = json.loads(workers_arg)
        workers = _parse_worker_defs(worker_defs, default_model=model)

    run_orchestrator_cli(
        workspace=workspace,
        goal=goal,
        workers=workers,
        model=model,
        transport=transport,
        proxy_url=getattr(args, "proxy", None),
        no_ssl_verify=getattr(args, "no_ssl_verify", False),
    )


def cmd_build(args):
    """Build a standalone agent binary from a config file (JSON or TOML)."""
    config_path = os.path.abspath(args.config)
    if not os.path.isfile(config_path):
        print(f"[!] Config file not found: {config_path}")
        sys.exit(1)

    ext = os.path.splitext(config_path)[1].lower()
    with open(config_path, "rb") as f:
        if ext == ".toml":
            config = tomllib.load(f)
        else:
            config = json.load(f)

    # CLI overrides
    if args.name:
        config["name"] = args.name
    if args.model:
        config["model"] = args.model
    if args.output:
        output_dir = os.path.abspath(args.output)
    else:
        name = config.get("name", "agent")
        output_dir = os.path.expanduser(f"~/.copilot-cli/builds/{name}")

    os.makedirs(output_dir, exist_ok=True)

    # Choose build mode
    script_only = getattr(args, "script_only", False)

    def on_progress(step_type, message):
        prefix = {"step": "\033[94m⏺\033[0m", "log": "  ", "error": "\033[31m!\033[0m"}
        print(f"{prefix.get(step_type, '  ')} {message}")

    try:
        from agent_builder.export import build_agent, export_script

        if script_only:
            entry, cfg_path = export_script(config, output_dir)
            print(f"\033[94m⏺\033[0m Exported script: {entry}")
            print(f"  Config: {cfg_path}")
        else:
            binary = build_agent(config, output_dir, on_progress)
            print(f"\n\033[32m⏺\033[0m Built: {binary}")
    except ImportError:
        print("[!] agent_builder package not found. "
              "Make sure agent-builder/src is on PYTHONPATH.")
        sys.exit(1)
    except RuntimeError as e:
        print(f"\033[31m[!] Build failed: {e}\033[0m")
        sys.exit(1)


def main():
    parser = argparse.ArgumentParser(
        prog="copilot",
        description="GitHub Copilot Language Server CLI",
    )
    config_workspace = CONFIG.get("workspace")
    if config_workspace:
        config_workspace = os.path.expanduser(config_workspace)
    parser.add_argument("-w", "--workspace", default=config_workspace or os.getcwd(),
                        help="Workspace directory (default: config.toml workspace or cwd)")
    parser.add_argument("--mcp", default=None,
                        help="MCP server config: path to JSON file or inline JSON string. "
                             "Auto-routes to server-side or client-side based on org policy.")
    parser.add_argument("--proxy", default=None,
                        help="Proxy URL (e.g. http://host:port or http://user:pass@host:port)")
    parser.add_argument("--no-ssl-verify", action="store_true", default=False,
                        help="Disable SSL certificate verification for proxy connections")
    parser.add_argument("-v", "--verbose", action="store_true", default=False,
                        help="Show full tool call results (default: only show tool invocation lines)")
    sub = parser.add_subparsers(dest="command")

    # --- models ---
    sub.add_parser("models", help="List available models")

    # --- complete ---
    p_comp = sub.add_parser("complete", help="Get inline completions for a file")
    p_comp.add_argument("file", help="File to complete")
    p_comp.add_argument("-l", "--line", type=int, default=None, help="Line number (0-based, default: end)")
    p_comp.add_argument("-c", "--character", type=int, default=None, help="Column (0-based, default: end of line)")

    # --- chat ---
    p_chat = sub.add_parser("chat", help="Interactive chat (or one-shot with -m)")
    p_chat.add_argument("prompt", nargs="*", help="Prompt (omit for interactive mode)")
    p_chat.add_argument("-m", "--model", default=None, help="Model ID (e.g. claude-sonnet-4)")
    p_chat.add_argument("-a", "--agent", action="store_true", help="Enable agent mode (file edits, terminal)")

    # --- agent (shortcut for chat --agent) ---
    p_agent = sub.add_parser("agent", help="Interactive agent mode (chat + tools)")
    p_agent.add_argument("prompt", nargs="*", help="Prompt (omit for interactive mode)")
    p_agent.add_argument("-m", "--model", default=None, help="Model ID")

    # --- orchestrate (multi-agent orchestrator) ---
    p_orch = sub.add_parser("orchestrate", help="Multi-agent orchestrator mode")
    p_orch.add_argument("prompt", nargs="+", help="High-level goal for the orchestrator")
    p_orch.add_argument("-m", "--model", default=None, help="Model ID for orchestrator and workers")
    p_orch.add_argument("--config", default=None,
                        help="Orchestrator config file (TOML/JSON) defining workers, "
                             "system prompt, model, and transport in one file")
    p_orch.add_argument("--workers", default=None,
                        help="Worker config: JSON file or inline JSON array of "
                             '{role, system_prompt, model, tools_enabled}')
    p_orch.add_argument("--transport", choices=["mcp", "queue"], default=None,
                        help="Agent transport: 'mcp' (workers as MCP servers, default) "
                             "or 'queue' (in-process threads)")

    # --- build (build agent from config file) ---
    p_build = sub.add_parser("build", help="Build a standalone agent binary from a config file")
    p_build.add_argument("config", help="Path to agent config (JSON or TOML)")
    p_build.add_argument("-n", "--name", default=None, help="Override agent name")
    p_build.add_argument("-m", "--model", default=None, help="Override model ID")
    p_build.add_argument("-o", "--output", default=None, help="Output directory (default: ~/.copilot-cli/builds/<name>)")
    p_build.add_argument("--script-only", action="store_true", default=False,
                         help="Export as a Python script instead of a PyInstaller binary")

    # --- mcp (MCP server management) ---
    p_mcp = sub.add_parser("mcp", help="MCP server management")
    p_mcp.add_argument("mcp_action", choices=["list", "tools", "start", "stop", "restart"],
                       help="Action: list servers, list tools, start/stop/restart a server")
    p_mcp.add_argument("server_name", nargs="?", default=None,
                       help="Server name (required for start/stop/restart)")

    args = parser.parse_args()

    # Apply config file defaults - CLI args take precedence
    proxy_cfg = CONFIG.get("proxy", {}) or {}
    if not args.proxy and proxy_cfg.get("url"):
        args.proxy = proxy_cfg["url"]
    if not args.no_ssl_verify and proxy_cfg.get("no_ssl_verify"):
        args.no_ssl_verify = True
    if not getattr(args, "mcp", None):
        # Check config for [mcp] section; also accept legacy [client_mcp]
        mcp_val = CONFIG.get("mcp") or CONFIG.get("client_mcp")
        if mcp_val:
            args.mcp = json.dumps(mcp_val) if isinstance(mcp_val, dict) else mcp_val
    if not getattr(args, "model", None) and CONFIG.get("default_model"):
        args.model = CONFIG["default_model"]

    if args.command == "models":
        cmd_models(args)
    elif args.command == "complete":
        cmd_complete(args)
    elif args.command == "agent":
        args.agent = True
        cmd_chat(args)
    elif args.command == "chat":
        cmd_chat(args)
    elif args.command == "orchestrate":
        cmd_orchestrate(args)
    elif args.command == "build":
        cmd_build(args)
    elif args.command == "mcp":
        cmd_mcp(args)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()