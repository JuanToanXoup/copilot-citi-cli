# Client-Side MCP Bridge

When your organization disables MCP at the GitHub policy level, the Copilot language server refuses to start MCP servers regardless of client configuration. The client-side MCP bridge bypasses this by running MCP servers directly from the CLI and registering their tools as regular client tools.

## How It Works

```
Server-side MCP (blockable):
  CLI → language server → MCP server
               ↑
         GitHub policy blocks here

Client-side MCP (not blockable):
  CLI → spawns MCP server directly (stdio)
  CLI → discovers tools via MCP protocol (initialize → tools/list)
  CLI → registers tools as client tools (conversation/registerTools)
  CLI → bridges tool calls (conversation/invokeClientTool → tools/call)
```

The language server sees client-MCP tools as regular registered client tools — identical to built-in tools like `read_file` or `run_in_terminal`. It has no way to distinguish them from MCP-backed tools, so no policy check can block them.

## Quick Start

The CLI auto-routes MCP: if your org allows server-side MCP, the language server manages it. If blocked, the CLI transparently falls back to client-side MCP. You always use the same `--mcp` flag.

```bash
# Create a config file
echo '{"playwright":{"command":"npx","args":["-y","@playwright/mcp@latest"]}}' > mcp.json

# Run with --mcp — auto-routes to client-side if org blocks server-side MCP
python3 copilot_client.py --mcp mcp.json agent "Navigate to example.com"
```

## Config Format

Each key is a server name, value defines how to run it:

```json
{
  "<server-name>": {
    "command": "<executable>",
    "args": ["<arg1>", "<arg2>"],
    "env": { "KEY": "VALUE" }
  }
}
```

Only stdio transport is supported for client-side MCP (the CLI spawns the process directly). HTTP/streamable transport is only available when the language server manages MCP (server-side route).

## Server-Side vs Client-Side MCP

The `--mcp` flag auto-detects which route to use. You don't need to choose manually.

| | Server-Side (org allows MCP) | Client-Side (org blocks MCP) |
|---|---|---|
| Who manages the MCP server | Language server | CLI (Python) |
| MCP protocol | Language server ↔ MCP server | CLI ↔ MCP server |
| Tools visible as | MCP tools | Regular client tools |
| Subject to org policy | Yes | No |
| Supports stdio | Yes | Yes |
| Supports HTTP/streamable | Yes | No |
| Tool name format | `browser_navigate` | `mcp_playwright_browser_navigate` |

## Examples

### Playwright (browser automation)

```json
{
  "playwright": {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest"]
  }
}
```

```bash
python3 copilot_client.py --mcp mcp.json agent "Navigate to example.com and take a screenshot"
```

Output:
```
[client-mcp] Started 'playwright' (PID: 7626)
[client-mcp] 'playwright' initialized: Playwright v0.0.64
[client-mcp] 'playwright': 22 tool(s) discovered
  - browser_navigate: Navigate to a URL
  - browser_click: Perform click on a web page
  - browser_snapshot: Capture accessibility snapshot of the current page
  ...
[*] Registered 29 client tools (+15 built-in + 22 client-mcp)
```

### Multiple servers

```json
{
  "playwright": {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest"]
  },
  "memory": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-memory"]
  }
}
```

### Custom Python MCP server

```json
{
  "my-tool": {
    "command": "python3",
    "args": ["/path/to/my_mcp_server.py"]
  }
}
```

### With proxy

```bash
python3 copilot_client.py \
  --mcp mcp.json \
  --proxy http://localhost:3128 \
  agent "your prompt"
```

Note: the proxy applies to the Copilot language server's HTTP requests. MCP servers spawned client-side inherit the parent process environment, so if the MCP server makes outbound HTTP requests, set proxy env vars in the config:

```json
{
  "my-server": {
    "command": "npx",
    "args": ["-y", "some-mcp-server"],
    "env": {
      "HTTPS_PROXY": "http://localhost:3128"
    }
  }
}
```

## How Tool Names Are Mapped

Client-side MCP tools are prefixed with `mcp_<server-name>_` to avoid collisions with built-in tools:

| MCP tool name | Registered as |
|---|---|
| `browser_navigate` | `mcp_playwright_browser_navigate` |
| `browser_click` | `mcp_playwright_browser_click` |
| `store` | `mcp_memory_store` |

The AI agent sees these prefixed names in its tool list and calls them by their full name. The CLI strips the prefix and forwards the call to the correct MCP server.

## How Tool Calls Are Bridged

```
1. Agent decides to call mcp_playwright_browser_navigate
2. Server sends conversation/invokeClientToolConfirmation → CLI auto-accepts
3. Server sends conversation/invokeClientTool with tool name + arguments
4. CLI recognizes the mcp_ prefix → looks up the MCP server
5. CLI sends tools/call to the Playwright MCP process via stdio
6. Playwright executes the action and returns the result
7. CLI wraps the result in the registered tool response format
8. CLI sends the response back to the language server
9. Agent receives the result and continues
```

## Internals

### MCP Protocol (stdio transport)

MCP uses **newline-delimited JSON-RPC 2.0** over stdio. Each message is a single line of JSON terminated by `\n`. This is different from LSP which uses `Content-Length` headers.

Handshake sequence:
```
Client → Server:  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
Server → Client:  {"jsonrpc":"2.0","id":1,"result":{"serverInfo":{...},...}}
Client → Server:  {"jsonrpc":"2.0","method":"notifications/initialized"}
Client → Server:  {"jsonrpc":"2.0","id":2,"method":"tools/list"}
Server → Client:  {"jsonrpc":"2.0","id":2,"result":{"tools":[...]}}
```

Tool call:
```
Client → Server:  {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser_navigate","arguments":{"url":"..."}}}
Server → Client:  {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"..."}]}}
```

### Architecture

```
client_mcp.py
├── MCPServer          — manages one MCP process (spawn, initialize, tools/list, tools/call)
└── ClientMCPManager   — manages multiple servers, builds tool map, bridges calls

copilot_client.py
├── --mcp flag         — loads config, auto-routes to server or client-side
├── _init_client()     — creates ClientMCPManager, calls start_all()
├── register_client_tools() — includes client-MCP tool schemas
├── _execute_client_tool()  — checks client-MCP first, forwards tools/call
└── stop()             — cleans up MCP server processes
```

### Response Format

Client-side MCP tools return the registered tool tuple format that the Copilot server expects:

```python
[{"content": [{"value": "result text"}], "status": "success"}, None]
```

This is handled automatically — MCP `tools/call` results are converted to this format in `_execute_client_tool()`.

## Troubleshooting

### "Failed to start" with timeout

The MCP server process may be slow to start (e.g., `npx` downloading packages). The default timeout is 30 seconds. If your server needs more time, ensure the package is pre-installed:

```bash
npm install -g @playwright/mcp
```

Then use the global binary directly:

```json
{
  "playwright": {
    "command": "playwright-mcp",
    "args": []
  }
}
```

### Tools registered but agent doesn't use them

Make sure you're using the `agent` subcommand (not `chat`). Client-MCP tools are only registered in agent mode.

### "Broken pipe" errors

The MCP server process crashed. Check the `[client-mcp:<name>]` stderr output for error details. Common causes:
- Missing dependencies (run `npx -y <package>` manually first)
- Invalid environment variables in the config

### Tool call returns empty result

Some MCP tools return image content (`type: "image"`) which the bridge currently extracts as empty text. Only `type: "text"` content is forwarded.
