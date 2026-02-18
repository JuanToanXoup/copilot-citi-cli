"""Agent Builder — HTTP server + API for the visual agent composition UI."""

import json
import os
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote

# ── Constants ────────────────────────────────────────────────────────────────

AGENTS_DIR = os.path.expanduser("~/.copilot-cli/agents")
STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")

KNOWN_MODELS = [
    {"id": "gpt-4.1", "name": "GPT-4.1"},
    {"id": "gpt-4.1-mini", "name": "GPT-4.1 Mini"},
    {"id": "claude-sonnet-4", "name": "Claude Sonnet 4"},
    {"id": "gemini-2.5-pro", "name": "Gemini 2.5 Pro"},
    {"id": "o4-mini", "name": "o4-mini"},
]

MIME_TYPES = {
    ".html": "text/html",
    ".css": "text/css",
    ".js": "application/javascript",
    ".json": "application/json",
    ".png": "image/png",
    ".svg": "image/svg+xml",
}

# ── Session management ───────────────────────────────────────────────────────

_sessions = {}        # session_id → {client, config, conversation_id, last_active}
_sessions_lock = threading.Lock()


def _cleanup_sessions(max_idle=600):
    """Stop clients idle for more than max_idle seconds."""
    while True:
        time.sleep(60)
        now = time.time()
        with _sessions_lock:
            expired = [sid for sid, s in _sessions.items()
                       if now - s["last_active"] > max_idle]
            for sid in expired:
                try:
                    _sessions[sid]["client"].stop()
                except Exception:
                    pass
                del _sessions[sid]


# ── Request Handler ──────────────────────────────────────────────────────────

class BuilderHandler(BaseHTTPRequestHandler):

    def log_message(self, fmt, *a):
        # Suppress default access log noise
        pass

    def handle(self):
        """Suppress ConnectionResetError from HTTP/1.0 + keep-alive mismatch."""
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            pass

    # ── Routing ──────────────────────────────────────────────────────────

    def do_GET(self):
        path = urlparse(self.path).path

        if path == "/api/tools":
            self._api_get_tools()
        elif path == "/api/models":
            self._api_get_models()
        elif path == "/api/templates":
            self._api_list_templates()
        elif path.startswith("/api/templates/"):
            self._api_get_template(path.split("/api/templates/", 1)[1])
        elif path == "/api/configs":
            self._api_list_configs()
        elif path.startswith("/api/configs/"):
            name = unquote(path.split("/api/configs/", 1)[1])
            self._api_get_config(name)
        else:
            self._serve_static(path)

    def do_POST(self):
        path = urlparse(self.path).path
        body = self._read_body()

        if path == "/api/configs":
            self._api_save_config(body)
        elif path == "/api/preview/start":
            self._api_preview_start(body)
        elif path == "/api/preview/chat":
            self._api_preview_chat(body)
        elif path == "/api/preview/stop":
            self._api_preview_stop(body)
        elif path == "/api/build":
            self._api_build(body)
        elif path == "/api/export-script":
            self._api_export_script(body)
        else:
            self._json_response(404, {"error": "not found"})

    def do_DELETE(self):
        path = urlparse(self.path).path
        if path.startswith("/api/configs/"):
            name = unquote(path.split("/api/configs/", 1)[1])
            self._api_delete_config(name)
        else:
            self._json_response(404, {"error": "not found"})

    # ── Helpers ──────────────────────────────────────────────────────────

    def _read_body(self) -> dict:
        length = int(self.headers.get("Content-Length", 0))
        if length == 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}

    def _json_response(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def _sse_start(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

    def _sse_send(self, data):
        """Send an SSE event. Returns True on success, False if client disconnected."""
        line = f"data: {json.dumps(data)}\n\n"
        try:
            self.wfile.write(line.encode())
            self.wfile.flush()
            return True
        except (BrokenPipeError, ConnectionResetError):
            return False

    def _serve_static(self, path):
        if path == "/" or path == "":
            path = "/index.html"
        file_path = os.path.join(STATIC_DIR, path.lstrip("/"))
        file_path = os.path.normpath(file_path)

        # Security: ensure we stay within STATIC_DIR
        if not file_path.startswith(os.path.normpath(STATIC_DIR)):
            self.send_error(403)
            return

        if not os.path.isfile(file_path):
            self.send_error(404)
            return

        ext = os.path.splitext(file_path)[1].lower()
        mime = MIME_TYPES.get(ext, "application/octet-stream")

        with open(file_path, "rb") as f:
            content = f.read()

        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    # ── API: Tools ───────────────────────────────────────────────────────

    def _api_get_tools(self):
        from copilot_cli.tools import TOOL_SCHEMAS, BUILTIN_TOOL_NAMES
        result = {}
        for name, schema in TOOL_SCHEMAS.items():
            result[name] = {
                "name": name,
                "description": schema.get("description", ""),
                "_builtin": name in BUILTIN_TOOL_NAMES,
            }
        self._json_response(200, result)

    # ── API: Models ──────────────────────────────────────────────────────

    def _api_get_models(self):
        self._json_response(200, KNOWN_MODELS)

    # ── API: Templates ───────────────────────────────────────────────────

    def _api_list_templates(self):
        from agent_builder.templates import list_templates
        self._json_response(200, list_templates())

    def _api_get_template(self, template_id):
        from agent_builder.templates import get_template
        t = get_template(unquote(template_id))
        if t:
            self._json_response(200, t)
        else:
            self._json_response(404, {"error": "template not found"})

    # ── API: Config CRUD ─────────────────────────────────────────────────

    def _api_list_configs(self):
        os.makedirs(AGENTS_DIR, exist_ok=True)
        configs = []
        for f in sorted(os.listdir(AGENTS_DIR)):
            if f.endswith(".json"):
                configs.append(f[:-5])
        self._json_response(200, configs)

    def _api_get_config(self, name):
        path = os.path.join(AGENTS_DIR, f"{name}.json")
        if not os.path.isfile(path):
            self._json_response(404, {"error": "config not found"})
            return
        with open(path, "r") as f:
            self._json_response(200, json.load(f))

    def _api_save_config(self, body):
        name = body.get("name", "").strip()
        if not name:
            self._json_response(400, {"error": "name required"})
            return
        # Sanitize name for filesystem
        safe_name = "".join(c for c in name if c.isalnum() or c in "-_").strip()
        if not safe_name:
            self._json_response(400, {"error": "invalid name"})
            return
        os.makedirs(AGENTS_DIR, exist_ok=True)
        path = os.path.join(AGENTS_DIR, f"{safe_name}.json")
        with open(path, "w") as f:
            json.dump(body, f, indent=2)
        self._json_response(200, {"ok": True, "path": path})

    def _api_delete_config(self, name):
        path = os.path.join(AGENTS_DIR, f"{name}.json")
        if os.path.isfile(path):
            os.remove(path)
            self._json_response(200, {"ok": True})
        else:
            self._json_response(404, {"error": "config not found"})

    # ── API: Live Preview ────────────────────────────────────────────────

    def _api_preview_start(self, body):
        self._sse_start()
        try:
            from copilot_cli.client import _init_client, _load_config
            config = body
            workspace = config.get("workspace_root") or "/tmp/copilot-workspace"
            workspace = os.path.expanduser(workspace)
            os.makedirs(workspace, exist_ok=True)
            agent_mode = config.get("agent_mode", True)

            # Build MCP config from agent config
            mcp_config = config.get("mcp_servers") or None
            if mcp_config and not mcp_config:
                mcp_config = None

            # Proxy
            proxy_url = None
            no_ssl_verify = False
            proxy_cfg = config.get("proxy", {})
            if proxy_cfg:
                proxy_url = proxy_cfg.get("url") or None
                no_ssl_verify = proxy_cfg.get("no_ssl_verify", False)

            def on_progress(message):
                self._sse_send({"type": "progress", "message": message})

            client = _init_client(
                workspace,
                agent_mode=agent_mode,
                mcp_config=mcp_config,
                proxy_url=proxy_url,
                no_ssl_verify=no_ssl_verify,
                verbose=False,
                on_progress=on_progress,
            )

            # Check if client disconnected during init (user cancelled)
            session_id = uuid.uuid4().hex[:12]
            ok = self._sse_send({"type": "done", "session_id": session_id})
            if ok:
                with _sessions_lock:
                    _sessions[session_id] = {
                        "client": client,
                        "config": config,
                        "conversation_id": None,
                        "last_active": time.time(),
                    }
            else:
                # Client disconnected during startup — clean up
                try:
                    client.stop()
                except Exception:
                    pass

        except Exception as e:
            self._sse_send({"type": "error", "message": str(e)})

    def _api_preview_chat(self, body):
        session_id = body.get("session_id")
        message = body.get("message", "")
        conv_id = body.get("conversation_id")

        with _sessions_lock:
            session = _sessions.get(session_id)
            if not session:
                self._json_response(404, {"error": "session not found"})
                return
            session["last_active"] = time.time()
            client = session["client"]
            config = session["config"]
            if conv_id:
                session["conversation_id"] = conv_id

        # Prepend system prompt to first message
        system_prompt = config.get("system_prompt", "")
        actual_message = message
        if system_prompt and not session.get("conversation_id"):
            actual_message = f"<system_instructions>{system_prompt}</system_instructions>\n\n{message}"

        self._sse_start()

        model = config.get("model")
        agent_mode = config.get("agent_mode", True)
        workspace_uri = None
        ws = config.get("workspace_root")
        if ws and agent_mode:
            from copilot_cli.platform_utils import path_to_file_uri
            workspace_uri = path_to_file_uri(os.path.expanduser(ws))

        sent_text = []  # track text already sent via delta events

        def on_progress(kind, data):
            if kind == "delta":
                delta = data.get("delta", "")
                if delta:
                    sent_text.append(delta)
                    self._sse_send({"type": "delta", "data": delta})
            elif kind == "agent_round":
                # Agent rounds contain tool calls and reply text
                # Extract tool call info from the round
                steps = data.get("steps", [])
                for step in steps:
                    tool_name = step.get("toolName") or step.get("name", "")
                    if tool_name:
                        self._sse_send({"type": "tool_call", "name": tool_name})
                # If no steps but has description, report that
                desc = data.get("description", "")
                if not steps and desc:
                    self._sse_send({"type": "tool_call", "name": desc})
                # Forward reply text from the round
                reply = data.get("reply", "")
                if reply:
                    sent_text.append(reply)
                    self._sse_send({"type": "delta", "data": reply})
            elif kind == "done":
                pass  # handled after call returns

        try:
            if not session["conversation_id"]:
                result = client.conversation_create(
                    actual_message,
                    model=model,
                    agent_mode=agent_mode,
                    workspace_folder=workspace_uri,
                    on_progress=on_progress,
                )
                session["conversation_id"] = result["conversationId"]
            else:
                result = client.conversation_turn(
                    session["conversation_id"],
                    actual_message,
                    model=model,
                    agent_mode=agent_mode,
                    on_progress=on_progress,
                )

            # Send any reply text not already sent via deltas
            full_reply = result.get("reply", "")
            already_sent = "".join(sent_text)
            if full_reply and full_reply != already_sent:
                # Send the portion not yet streamed
                unsent = full_reply
                if already_sent and full_reply.startswith(already_sent):
                    unsent = full_reply[len(already_sent):]
                if unsent:
                    self._sse_send({"type": "delta", "data": unsent})

            self._sse_send({
                "type": "done",
                "conversation_id": session.get("conversation_id"),
                "reply": full_reply,
            })
        except Exception as e:
            self._sse_send({"type": "error", "data": str(e)})

    def _api_preview_stop(self, body):
        session_id = body.get("session_id")
        with _sessions_lock:
            session = _sessions.pop(session_id, None)
        if session:
            try:
                session["client"].stop()
            except Exception:
                pass
            self._json_response(200, {"ok": True})
        else:
            self._json_response(404, {"error": "session not found"})

    # ── API: Build ───────────────────────────────────────────────────────

    def _api_build(self, body):
        self._sse_start()
        try:
            from agent_builder.export import build_agent
            config = body
            name = config.get("name", "agent").strip()
            output_dir = os.path.expanduser(f"~/.copilot-cli/builds/{name}")
            os.makedirs(output_dir, exist_ok=True)

            def on_progress(step, message):
                self._sse_send({"type": step, "message": message})

            result_path = build_agent(config, output_dir, on_progress)
            self._sse_send({"type": "done", "path": result_path})
        except Exception as e:
            self._sse_send({"type": "error", "message": str(e)})

    def _api_export_script(self, body):
        try:
            from agent_builder.export import export_script
            config = body
            name = config.get("name", "agent").strip()
            output_dir = os.path.expanduser(f"~/.copilot-cli/builds/{name}")
            os.makedirs(output_dir, exist_ok=True)
            entry_point, config_path = export_script(config, output_dir)
            self._json_response(200, {
                "ok": True,
                "entry_point": entry_point,
                "config_path": config_path,
            })
        except Exception as e:
            self._json_response(500, {"error": str(e)})


# ── Server entry point ───────────────────────────────────────────────────────

def start_server(port=8420, open_browser=True):
    """Start the Agent Builder HTTP server."""
    ThreadingHTTPServer.allow_reuse_address = True
    try:
        server = ThreadingHTTPServer(("127.0.0.1", port), BuilderHandler)
    except OSError as e:
        if e.errno == 48:  # Address already in use
            print(f"\n  Port {port} is already in use. Attempting to stop the existing server...")
            import signal
            import subprocess
            result = subprocess.run(["lsof", "-ti", f":{port}"], capture_output=True, text=True)
            if result.stdout.strip():
                for pid in result.stdout.strip().split("\n"):
                    os.kill(int(pid), signal.SIGTERM)
                import time
                time.sleep(1)
            server = ThreadingHTTPServer(("127.0.0.1", port), BuilderHandler)
        else:
            raise
    print(f"\n  \033[94m╭─ Agent Builder\033[0m")
    print(f"  \033[94m│\033[0m  http://localhost:{port}")
    print(f"  \033[94m╰─\033[0m\n")

    # Start session cleanup thread
    cleaner = threading.Thread(target=_cleanup_sessions, daemon=True)
    cleaner.start()

    if open_browser:
        import webbrowser
        webbrowser.open(f"http://localhost:{port}")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Shutting down Agent Builder...")
        # Stop all active sessions
        with _sessions_lock:
            for sid, session in _sessions.items():
                try:
                    session["client"].stop()
                except Exception:
                    pass
            _sessions.clear()
        server.shutdown()
