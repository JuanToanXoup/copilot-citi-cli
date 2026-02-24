"""Minimal MCP server over stdio (JSON-RPC 2.0, newline-delimited).

No external dependencies — reads stdin, writes stdout, line by line.
"""

import json
import sys
from typing import Any

SERVER_INFO = {
    "name": "speckit-mcp",
    "version": "0.1.0",
}

PROTOCOL_VERSION = "2024-11-05"


class MCPServer:
    """Lightweight MCP server that dispatches tool calls and resource reads."""

    def __init__(self):
        self._tools: dict[str, dict] = {}
        self._tool_handlers: dict[str, Any] = {}
        self._resources: dict[str, dict] = {}
        self._resource_handlers: dict[str, Any] = {}
        self._resource_templates: dict[str, dict] = {}
        self._resource_template_handlers: dict[str, Any] = {}

    # -- registration helpers ------------------------------------------------

    def tool(self, name: str, description: str, input_schema: dict):
        """Decorator to register an MCP tool."""
        def decorator(fn):
            self._tools[name] = {
                "name": name,
                "description": description,
                "inputSchema": input_schema,
            }
            self._tool_handlers[name] = fn
            return fn
        return decorator

    def resource(self, uri: str, name: str, description: str, mime_type: str = "text/plain"):
        """Decorator to register a static MCP resource."""
        def decorator(fn):
            self._resources[uri] = {
                "uri": uri,
                "name": name,
                "description": description,
                "mimeType": mime_type,
            }
            self._resource_handlers[uri] = fn
            return fn
        return decorator

    def resource_template(self, uri_template: str, name: str, description: str,
                          mime_type: str = "text/plain"):
        """Decorator to register an MCP resource template."""
        def decorator(fn):
            self._resource_templates[uri_template] = {
                "uriTemplate": uri_template,
                "name": name,
                "description": description,
                "mimeType": mime_type,
            }
            self._resource_template_handlers[uri_template] = fn
            return fn
        return decorator

    # -- protocol handling ---------------------------------------------------

    def _handle_initialize(self, params: dict) -> dict:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {
                "tools": {"listChanged": False},
                "resources": {"subscribe": False, "listChanged": False},
            },
            "serverInfo": SERVER_INFO,
        }

    def _handle_tools_list(self, params: dict) -> dict:
        return {"tools": list(self._tools.values())}

    def _handle_tools_call(self, params: dict) -> dict:
        name = params.get("name", "")
        arguments = params.get("arguments", {})
        handler = self._tool_handlers.get(name)
        if handler is None:
            return {
                "content": [{"type": "text", "text": f"Unknown tool: {name}"}],
                "isError": True,
            }
        try:
            result = handler(arguments)
            if isinstance(result, str):
                return {"content": [{"type": "text", "text": result}]}
            return result
        except Exception as e:
            return {
                "content": [{"type": "text", "text": f"Error: {e}"}],
                "isError": True,
            }

    def _handle_resources_list(self, params: dict) -> dict:
        return {"resources": list(self._resources.values())}

    def _handle_resources_templates_list(self, params: dict) -> dict:
        return {"resourceTemplates": list(self._resource_templates.values())}

    def _handle_resources_read(self, params: dict) -> dict:
        uri = params.get("uri", "")
        # Try exact match first
        handler = self._resource_handlers.get(uri)
        if handler:
            content = handler(uri)
            return {"contents": [{"uri": uri, "text": content, "mimeType": "text/plain"}]}
        # Try templates
        for template_uri, tmpl_handler in self._resource_template_handlers.items():
            match = _match_uri_template(template_uri, uri)
            if match is not None:
                content = tmpl_handler(uri, match)
                return {"contents": [{"uri": uri, "text": content, "mimeType": "text/markdown"}]}
        return {"contents": [{"uri": uri, "text": f"Resource not found: {uri}"}]}

    def _dispatch(self, method: str, params: dict) -> dict | None:
        handlers = {
            "initialize": self._handle_initialize,
            "tools/list": self._handle_tools_list,
            "tools/call": self._handle_tools_call,
            "resources/list": self._handle_resources_list,
            "resources/templates/list": self._handle_resources_templates_list,
            "resources/read": self._handle_resources_read,
        }
        handler = handlers.get(method)
        if handler:
            return handler(params)
        # Notifications (no response needed)
        if method.startswith("notifications/"):
            return None
        return None

    def handle_message(self, msg: dict) -> dict | None:
        method = msg.get("method")
        msg_id = msg.get("id")
        params = msg.get("params", {})

        if method is None:
            return None

        result = self._dispatch(method, params)
        if result is not None and msg_id is not None:
            return {"jsonrpc": "2.0", "id": msg_id, "result": result}
        if msg_id is not None and result is None and not method.startswith("notifications/"):
            return {"jsonrpc": "2.0", "id": msg_id, "result": {}}
        return None

    def run_stdio(self):
        """Main loop: read JSON-RPC from stdin, write responses to stdout."""
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            response = self.handle_message(msg)
            if response is not None:
                sys.stdout.write(json.dumps(response) + "\n")
                sys.stdout.flush()


def _match_uri_template(template: str, uri: str) -> dict | None:
    """Simple URI template matching for {param} placeholders."""
    import re
    # Convert template to regex: speckit://prompt/{name} -> speckit://prompt/(?P<name>[^/]+)
    pattern = re.sub(r"\{(\w+)\}", r"(?P<\1>[^/]+)", re.escape(template).replace(r"\{", "{").replace(r"\}", "}"))
    # Fix: re.escape escapes { and }, undo that for our substitution
    escaped = re.escape(template)
    regex_pattern = re.sub(r"\\{(\w+)\\}", r"(?P<\1>[^/]+)", escaped)
    m = re.fullmatch(regex_pattern, uri)
    if m:
        return m.groupdict()
    return None
