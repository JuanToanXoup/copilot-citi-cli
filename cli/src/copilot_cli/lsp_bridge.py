"""
LSP Bridge — connects to real language servers for semantic code intelligence.

Manages language server processes (pyright, gopls, typescript-language-server, etc.)
via the Language Server Protocol (JSON-RPC 2.0 over stdio with Content-Length headers).

Tools use this bridge for diagnostics, references, symbols, and hover info,
falling back to grep/regex when no LSP server is available.
"""

import json
import os
import re
import shutil
import subprocess
import threading
import time


# Extension -> LSP language ID mapping
_EXT_TO_LANG = {
    ".py": "python", ".pyi": "python",
    ".js": "javascript", ".jsx": "javascriptreact",
    ".ts": "typescript", ".tsx": "typescriptreact",
    ".go": "go",
    ".rs": "rust",
    ".java": "java",
    ".c": "c", ".h": "c",
    ".cpp": "cpp", ".cxx": "cpp", ".cc": "cpp", ".hpp": "cpp",
    ".cs": "csharp",
    ".rb": "ruby",
}

# Built-in defaults: language_id -> (command, args)
# Users can override via [lsp.<language>] in copilot_config.toml
_DEFAULT_SERVERS = {
    "python": ("pyright-langserver", ["--stdio"]),
    "typescript": ("typescript-language-server", ["--stdio"]),
    "javascript": ("typescript-language-server", ["--stdio"]),
    "typescriptreact": ("typescript-language-server", ["--stdio"]),
    "javascriptreact": ("typescript-language-server", ["--stdio"]),
    "go": ("gopls", ["serve"]),
    "rust": ("rust-analyzer", []),
    "java": ("jdtls", []),
}

# LSP SymbolKind enum -> human label
_SYMBOL_KINDS = {
    1: "File", 2: "Module", 3: "Namespace", 4: "Package", 5: "Class",
    6: "Method", 7: "Property", 8: "Field", 9: "Constructor", 10: "Enum",
    11: "Interface", 12: "Function", 13: "Variable", 14: "Constant",
    15: "String", 16: "Number", 17: "Boolean", 18: "Array", 19: "Object",
    20: "Key", 21: "Null", 22: "EnumMember", 23: "Struct", 24: "Event",
    25: "Operator", 26: "TypeParameter",
}


def _path_to_uri(path: str) -> str:
    """Convert a file path to a file:// URI."""
    path = os.path.abspath(path)
    # On Windows, drive letters need special handling
    if os.name == "nt":
        path = "/" + path.replace("\\", "/")
    return "file://" + path


def _uri_to_path(uri: str) -> str:
    """Convert a file:// URI back to a local path."""
    if uri.startswith("file://"):
        path = uri[7:]
        if os.name == "nt" and path.startswith("/") and len(path) > 2 and path[2] == ":":
            path = path[1:]
        return path
    return uri


class LSPServer:
    """Manages a single LSP server process and communicates via JSON-RPC over stdio.

    Uses Content-Length header framing (standard LSP transport).
    """

    def __init__(self, language_id: str, command: str, args: list,
                 workspace_root: str):
        self.language_id = language_id
        self.command = command
        self.args = args
        self.workspace_root = workspace_root
        self.process = None
        self._buffer = b""
        self._responses = {}
        self._request_id = 0
        self._lock = threading.Lock()
        self._reader_thread = None
        self._diagnostics = {}  # uri -> list of diagnostic dicts
        self._open_docs = {}    # uri -> version
        self._initialized = False

    def start(self):
        """Spawn the language server process."""
        resolved = shutil.which(self.command) or self.command
        self.process = subprocess.Popen(
            [resolved] + self.args,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self._reader_thread = threading.Thread(target=self._reader_loop, daemon=True)
        self._reader_thread.start()

        # Drain stderr silently
        def _stderr_drain():
            while self.process and self.process.poll() is None:
                try:
                    self.process.stderr.readline()
                except Exception:
                    break
        threading.Thread(target=_stderr_drain, daemon=True).start()

    def initialize(self):
        """Send LSP initialize + initialized handshake."""
        root_uri = _path_to_uri(self.workspace_root)
        resp = self._send_request("initialize", {
            "processId": os.getpid(),
            "capabilities": {
                "textDocument": {
                    "publishDiagnostics": {"relatedInformation": True},
                    "hover": {"contentFormat": ["plaintext", "markdown"]},
                    "references": {},
                    "definition": {},
                },
                "workspace": {
                    "symbol": {"symbolKind": {"valueSet": list(range(1, 27))}},
                    "workspaceFolders": True,
                },
            },
            "rootUri": root_uri,
            "rootPath": self.workspace_root,
            "workspaceFolders": [
                {"uri": root_uri, "name": os.path.basename(self.workspace_root)},
            ],
        })
        self._send_notification("initialized", {})
        self._initialized = True
        return resp

    def stop(self):
        """Shutdown the language server."""
        if self.process and self.process.poll() is None:
            try:
                self._send_request("shutdown", None, timeout=5)
                self._send_notification("exit", None)
            except Exception:
                pass
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass

    # -- High-level methods for tools --

    def get_diagnostics(self, file_path: str, text: str) -> list[dict]:
        """Open/update a document and wait for pushed diagnostics."""
        uri = _path_to_uri(file_path)
        lang_id = _EXT_TO_LANG.get(os.path.splitext(file_path)[1].lower(),
                                    self.language_id)
        self._ensure_open(uri, lang_id, text)

        # Wait for diagnostics to arrive (servers push them asynchronously)
        deadline = time.time() + 10
        while time.time() < deadline:
            with self._lock:
                if uri in self._diagnostics:
                    return self._diagnostics[uri]
            time.sleep(0.2)
        # Return whatever we have (possibly empty)
        with self._lock:
            return self._diagnostics.get(uri, [])

    def find_references(self, file_path: str, line: int, character: int,
                        text: str) -> list[dict]:
        """Find all references to the symbol at the given position."""
        uri = _path_to_uri(file_path)
        lang_id = _EXT_TO_LANG.get(os.path.splitext(file_path)[1].lower(),
                                    self.language_id)
        self._ensure_open(uri, lang_id, text)

        resp = self._send_request("textDocument/references", {
            "textDocument": {"uri": uri},
            "position": {"line": line, "character": character},
            "context": {"includeDeclaration": True},
        }, timeout=30)
        return resp.get("result") or []

    def workspace_symbol(self, query: str) -> list[dict]:
        """Search for symbols across the workspace."""
        resp = self._send_request("workspace/symbol", {
            "query": query,
        }, timeout=30)
        return resp.get("result") or []

    def hover(self, file_path: str, line: int, character: int,
              text: str) -> str:
        """Get hover info (type signatures, docs) at the given position."""
        uri = _path_to_uri(file_path)
        lang_id = _EXT_TO_LANG.get(os.path.splitext(file_path)[1].lower(),
                                    self.language_id)
        self._ensure_open(uri, lang_id, text)

        resp = self._send_request("textDocument/hover", {
            "textDocument": {"uri": uri},
            "position": {"line": line, "character": character},
        }, timeout=15)
        result = resp.get("result")
        if not result:
            return ""
        contents = result.get("contents", "")
        return self._extract_hover_text(contents)

    # -- Internal helpers --

    def _ensure_open(self, uri: str, lang_id: str, text: str):
        """Open the document if not already opened, or update if content changed."""
        if uri not in self._open_docs:
            self._open_docs[uri] = 1
            self._send_notification("textDocument/didOpen", {
                "textDocument": {
                    "uri": uri,
                    "languageId": lang_id,
                    "version": 1,
                    "text": text,
                },
            })
        else:
            self._open_docs[uri] += 1
            self._send_notification("textDocument/didChange", {
                "textDocument": {"uri": uri, "version": self._open_docs[uri]},
                "contentChanges": [{"text": text}],
            })

    def _next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    def _encode_message(self, msg: dict) -> bytes:
        body = json.dumps(msg).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        return header + body

    def _send_request(self, method: str, params, timeout: int = 30) -> dict:
        msg_id = self._next_id()
        msg = {"jsonrpc": "2.0", "id": msg_id, "method": method}
        if params is not None:
            msg["params"] = params
        encoded = self._encode_message(msg)
        try:
            self.process.stdin.write(encoded)
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            return {"error": {"message": "LSP server pipe broken"}}

        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._lock:
                if msg_id in self._responses:
                    return self._responses.pop(msg_id)
            time.sleep(0.05)
        return {"error": {"message": f"LSP timeout: {method}"}}

    def _send_notification(self, method: str, params):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        try:
            encoded = self._encode_message(msg)
            self.process.stdin.write(encoded)
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    def _reader_loop(self):
        """Background thread: read Content-Length framed messages from stdout."""
        while self.process and self.process.poll() is None:
            try:
                data = self.process.stdout.read1(4096)
            except (OSError, AttributeError):
                break
            if not data:
                break
            with self._lock:
                self._buffer += data
                while True:
                    msg = self._parse_message()
                    if msg is None:
                        break
                    self._dispatch(msg)

    def _parse_message(self) -> dict | None:
        """Parse one LSP message from the buffer (called under lock)."""
        header_end = self._buffer.find(b"\r\n\r\n")
        if header_end == -1:
            return None
        header_section = self._buffer[:header_end].decode("ascii", errors="replace")
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

    def _dispatch(self, msg: dict):
        """Route a parsed message (called under lock)."""
        msg_id = msg.get("id")
        method = msg.get("method")

        if msg_id is not None and method is None:
            # Response to our request
            self._responses[msg_id] = msg
        elif method == "textDocument/publishDiagnostics":
            # Cache diagnostics keyed by URI
            params = msg.get("params", {})
            uri = params.get("uri", "")
            self._diagnostics[uri] = params.get("diagnostics", [])
        elif method == "window/logMessage" or method == "window/showMessage":
            pass  # Silently ignore log/show messages
        elif msg_id is not None and method is not None:
            # Server->client request — send empty response
            self._send_notification_raw_id(msg_id)

    def _send_notification_raw_id(self, req_id):
        """Send an empty response to a server->client request."""
        reply = {"jsonrpc": "2.0", "id": req_id, "result": None}
        try:
            encoded = self._encode_message(reply)
            self.process.stdin.write(encoded)
            self.process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    @staticmethod
    def _extract_hover_text(contents) -> str:
        """Extract readable text from LSP hover contents (various formats)."""
        if isinstance(contents, str):
            return contents
        if isinstance(contents, dict):
            return contents.get("value", str(contents))
        if isinstance(contents, list):
            parts = []
            for item in contents:
                if isinstance(item, str):
                    parts.append(item)
                elif isinstance(item, dict):
                    parts.append(item.get("value", ""))
            return "\n".join(parts)
        return str(contents)


class LSPBridgeManager:
    """Manages multiple LSP servers, one per language. Lazy startup."""

    def __init__(self, workspace_root: str, config: dict = None):
        self.workspace_root = workspace_root
        self._config = config or {}  # user overrides from [lsp] config section
        self._servers: dict[str, LSPServer] = {}  # language_id -> LSPServer
        self._lock = threading.Lock()

    def get_server(self, language_id: str) -> LSPServer | None:
        """Get (or lazily start) an LSP server for the given language.

        Returns None if no server is available for this language.
        """
        with self._lock:
            if language_id in self._servers:
                server = self._servers[language_id]
                if server.process and server.process.poll() is None:
                    return server
                # Server died — remove and retry
                del self._servers[language_id]

        # Check user config first, then built-in defaults
        command, args = self._resolve_server(language_id)
        if not command:
            return None

        # Verify the command exists
        if not shutil.which(command):
            return None

        server = LSPServer(language_id, command, args, self.workspace_root)
        try:
            server.start()
            server.initialize()
        except Exception:
            server.stop()
            return None

        with self._lock:
            self._servers[language_id] = server
        return server

    def get_server_for_file(self, file_path: str) -> LSPServer | None:
        """Get an LSP server based on file extension."""
        ext = os.path.splitext(file_path)[1].lower()
        lang_id = _EXT_TO_LANG.get(ext)
        if not lang_id:
            return None
        return self.get_server(lang_id)

    def find_symbol_position(self, name: str, file_path: str,
                             language_id: str = None) -> tuple | None:
        """Resolve a symbol name to (file_path, line, character).

        Tries workspace/symbol first, then falls back to text search.
        Returns None if the symbol cannot be found.
        """
        lang_id = language_id
        if not lang_id:
            ext = os.path.splitext(file_path)[1].lower()
            lang_id = _EXT_TO_LANG.get(ext)
        if not lang_id:
            return self._text_search_position(name, file_path)

        server = self.get_server(lang_id)
        if server:
            symbols = server.workspace_symbol(name)
            # Find exact match
            for sym in symbols:
                sym_name = sym.get("name", "")
                if sym_name == name:
                    loc = sym.get("location", {})
                    uri = loc.get("uri", "")
                    rng = loc.get("range", {}).get("start", {})
                    return (_uri_to_path(uri), rng.get("line", 0),
                            rng.get("character", 0))
            # If no exact match, try first partial match
            if symbols:
                loc = symbols[0].get("location", {})
                uri = loc.get("uri", "")
                rng = loc.get("range", {}).get("start", {})
                return (_uri_to_path(uri), rng.get("line", 0),
                        rng.get("character", 0))

        # Fallback: text search
        return self._text_search_position(name, file_path)

    def get_workspace_languages(self) -> list[str]:
        """Detect which languages are present in the workspace."""
        langs = set()
        for root, _, files in os.walk(self.workspace_root):
            # Skip hidden dirs and common non-code dirs
            parts = root.split(os.sep)
            if any(p.startswith(".") or p in ("node_modules", "__pycache__",
                                               "venv", ".venv", "vendor")
                   for p in parts):
                continue
            for fname in files:
                ext = os.path.splitext(fname)[1].lower()
                lang = _EXT_TO_LANG.get(ext)
                if lang:
                    langs.add(lang)
            if len(langs) >= 10:
                break
        return list(langs)

    def stop_all(self):
        """Stop all running LSP servers."""
        with self._lock:
            for server in self._servers.values():
                server.stop()
            self._servers.clear()

    def _resolve_server(self, language_id: str) -> tuple:
        """Resolve command + args for a language from config or defaults.

        Returns (command, args) or (None, None) if not available.
        """
        # Check user config: [lsp.python] command = "pyright-langserver"
        lang_cfg = self._config.get(language_id, {})
        if lang_cfg:
            cmd = lang_cfg.get("command")
            if cmd:
                return (cmd, lang_cfg.get("args", []))

        # Built-in defaults
        default = _DEFAULT_SERVERS.get(language_id)
        if default:
            return default
        return (None, None)

    @staticmethod
    def _text_search_position(name: str, file_path: str) -> tuple | None:
        """Fallback: find symbol position via simple text search."""
        if not os.path.isfile(file_path):
            return None
        try:
            with open(file_path, "r", errors="replace") as f:
                for lineno, line in enumerate(f):
                    # Look for definition patterns
                    if re.search(
                        rf"\b(def|class|function|func|fn|const|let|var|type|interface|struct|enum)\s+{re.escape(name)}\b",
                        line
                    ):
                        col = line.find(name)
                        return (file_path, lineno, max(col, 0))
                    # Also match plain occurrences
                    if name in line:
                        col = line.find(name)
                        return (file_path, lineno, max(col, 0))
        except OSError:
            pass
        return None
