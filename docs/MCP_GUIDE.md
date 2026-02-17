# Adding MCP Servers to Copilot CLI

This guide explains how to add any MCP (Model Context Protocol) server to the Copilot CLI agent. MCP servers provide external tools (browser automation, database access, APIs, etc.) that the AI agent can use automatically.

## How It Works

```
┌─────────────┐     stdio      ┌──────────────────────┐     stdio/http     ┌────────────┐
│  copilot CLI │ ──────────── > │ copilot-language-     │ ────────────────> │  MCP Server │
│  (Python)    │ < ──────────── │ server (Node.js)      │ < ──────────────── │  (any lang) │
└─────────────┘                └──────────────────────┘                    └────────────┘
      │                               │                                         │
      │ 1. Send MCP config            │ 2. Spawn & connect                      │
      │    via didChangeConfiguration │    to MCP server                        │
      │                               │ 3. Discover tools                       │
      │                               │ 4. AI agent calls tools ──────────────> │
      │                               │ 5. Results returned    <────────────── │
```

The Copilot language server handles all MCP communication. The CLI only needs to:
1. Send the MCP server config (a JSON object)
2. The server spawns the MCP process, discovers tools, and makes them available to the AI
3. No client-side tool code needed — execution is entirely server-side

## Config Format

MCP config is a JSON object where each key is a server name and the value defines how to run it.

### stdio transport (most common)

The server is a local process communicating over stdin/stdout:

```json
{
  "<server-name>": {
    "command": "<executable>",
    "args": ["<arg1>", "<arg2>", ...],
    "env": { "KEY": "VALUE" }
  }
}
```

### HTTP/streamable transport

The server is a remote HTTP endpoint:

```json
{
  "<server-name>": {
    "type": "streamable",
    "url": "https://example.com/mcp"
  }
}
```

The transport is auto-detected: if `"command"` is present it uses stdio, otherwise it checks `"type"` (defaults to `"streamable"`).

## Step-by-Step: Adding an MCP Server

### 1. Create a config file

Create a JSON file (e.g. `mcp.json`) with your server definition:

```json
{
  "playwright": {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest"]
  }
}
```

Multiple servers can be combined in one config:

```json
{
  "playwright": {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest"]
  },
  "filesystem": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/dir"]
  },
  "postgres": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-postgres"],
    "env": { "DATABASE_URL": "postgresql://user:pass@localhost/db" }
  }
}
```

### 2. Run with the `--mcp` flag

```bash
# From a config file
python3 copilot_client.py --mcp mcp.json -w ~/project agent "Your prompt here"

# Inline JSON (single server)
python3 copilot_client.py --mcp '{"playwright":{"command":"npx","args":["-y","@playwright/mcp@latest"]}}' agent
```

### 3. Verify the server connected

```bash
# List connected servers and their status
python3 copilot_client.py --mcp mcp.json mcp list

# List all tools provided by connected servers
python3 copilot_client.py --mcp mcp.json mcp tools
```

### 4. Manage servers at runtime

```bash
python3 copilot_client.py --mcp mcp.json mcp stop playwright
python3 copilot_client.py --mcp mcp.json mcp start playwright
python3 copilot_client.py --mcp mcp.json mcp restart playwright
```

## Common MCP Servers

| Server | Package | What it does |
|--------|---------|-------------|
| Playwright | `@playwright/mcp@latest` | Browser automation (navigate, click, screenshot, etc.) |
| Filesystem | `@modelcontextprotocol/server-filesystem` | Read/write files in a sandboxed directory |
| PostgreSQL | `@modelcontextprotocol/server-postgres` | Query a PostgreSQL database |
| GitHub | `@modelcontextprotocol/server-github` | GitHub API (issues, PRs, repos) |
| Slack | `@modelcontextprotocol/server-slack` | Send/read Slack messages |
| Puppeteer | `@modelcontextprotocol/server-puppeteer` | Browser automation via Puppeteer |
| Memory | `@modelcontextprotocol/server-memory` | Persistent key-value memory |
| Fetch | `@modelcontextprotocol/server-fetch` | HTTP fetch with HTML-to-markdown |

### Example configs

**Playwright** (browser automation):
```json
{
  "playwright": {
    "command": "npx",
    "args": ["-y", "@playwright/mcp@latest"]
  }
}
```

**GitHub** (requires a personal access token):
```json
{
  "github": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-github"],
    "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_..." }
  }
}
```

**Filesystem** (sandboxed to a directory):
```json
{
  "filesystem": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-filesystem", "/Users/me/projects"]
  }
}
```

**Custom Python MCP server**:
```json
{
  "my-tool": {
    "command": "python3",
    "args": ["/path/to/my_mcp_server.py"]
  }
}
```

## How the AI Agent Uses MCP Tools

MCP tools are **automatically available** to the agent. No registration or client-side code is needed.

When you run in agent mode (`agent` subcommand), the AI model sees MCP tools in its tool list alongside built-in tools (file editing, terminal, etc.). It decides when to call them based on the prompt.

The flow:
1. Agent decides to call an MCP tool (e.g. `browser_navigate`)
2. Server sends `conversation/invokeClientToolConfirmation` to the client
3. Client auto-accepts (current behavior)
4. Server executes the tool by communicating with the MCP server
5. Result flows back through progress notifications

You'll see output like:
```
[agent] Confirm tool: browser_navigate — auto-accepting
[agent tool] browser_navigate -> {"url": "https://example.com"}
[agent tool] browser_navigate done | output: Page Title: Example Domain
```

## Internals (for developers)

### How config reaches the server

The `configure_mcp()` method sends the config as a **JSON string** (not object) via LSP:

```python
client.send_notification("workspace/didChangeConfiguration", {
    "settings": {
        "github": {
            "copilot": {
                "mcp": json.dumps(mcp_config)  # Must be a JSON string
            }
        }
    }
})
```

### Notifications to watch

| Notification | Purpose |
|---|---|
| `copilot/mcpTools` | Fires when MCP servers connect/disconnect. Contains `params.servers` array with tool lists. |
| `copilot/mcpRuntimeLogs` | Runtime logs from MCP servers (connection state, errors). |

### Server management requests

| Method | Params | Purpose |
|---|---|---|
| `mcp/getTools` | `{}` | List all servers and their tools |
| `mcp/serverAction` | `{serverName, action}` | `start`, `stop`, `restart`, `logout`, `clearOAuth` |

### `mcp/getTools` response structure

```json
{
  "result": [
    {
      "name": "playwright",
      "prefix": "mcp_playwright",
      "status": "running",
      "tools": [
        {
          "name": "browser_navigate",
          "description": "Navigate to a URL",
          "inputSchema": { ... },
          "_status": "enabled"
        }
      ]
    }
  ]
}
```

### `copilot/mcpTools` notification structure

```json
{
  "method": "copilot/mcpTools",
  "params": {
    "servers": [
      {
        "name": "playwright",
        "prefix": "mcp_playwright",
        "status": "running",
        "tools": [ ... ]
      }
    ]
  }
}
```

## Troubleshooting

**Server shows 0 tools**: The MCP server may still be starting. The CLI waits 4 seconds after sending config. If your server is slow to start, increase the sleep in `_init_client()`.

**"Connection state: Running" but no tools**: Check `copilot/mcpRuntimeLogs` for errors. The MCP server process may have crashed — verify the command works standalone: `npx -y @playwright/mcp@latest`.

**Tools not appearing in agent**: Ensure you're using agent mode (`agent` subcommand, not `chat`). MCP tools are only available when `allSkills` is enabled.

**Environment variables**: Pass secrets via the `"env"` field in the config, not via shell environment, to avoid leaking them to other processes.
