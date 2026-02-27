# Copilot Language Server Internals

Findings from reverse-engineering `copilot-agent/dist/main.js` (v1.420.0). These are implementation details that affect how the CLI client must behave.

## Authentication

### Token Lookup Order

The server checks for tokens in this order:

1. `GH_COPILOT_TOKEN` env var — used directly, bypasses app-based auth. Sets user to literal `"<GH_COPILOT_TOKEN-user>"`.
2. `GITHUB_COPILOT_TOKEN` env var — same behavior as above.
3. `apps.json` persistence — reads `~/.config/github-copilot/apps.json`, looks for a token under the key `github.com:<appId>`.
4. `copilot/token` client request — if `copilotCapabilities.token: true` is set, the server sends a `copilot/token` request to the client asking for a token. **Do NOT enable this** unless you implement the handler.

### GitHub App IDs

The server has two hardcoded app IDs with their secrets:

| Variable | App ID | Purpose |
|---|---|---|
| `Gb` (default) | `Iv1.b507a08c87ecfe98` | Legacy app ID |
| `xft` | `Ov23liV9UpD7Rnfnskm3` | New/current app ID |

The server defaults to `Gb` and looks for tokens in `apps.json` under `github.com:Iv1.b507a08c87ecfe98`.

**Problem**: IntelliJ's Copilot plugin stores tokens under different app IDs (`Iv23ctfURkiMfJ4xr5mv`, `Ov23liV9UpD7Rnfnskm3`). The server may not find the token if the `Iv1.b507a08c87ecfe98` entry is missing.

**Fix**: The CLI reads the app ID from the `apps.json` key and passes it via `initializationOptions.githubAppId` to override the default. It also copies the token to the `Iv1.b507a08c87ecfe98` key as a fallback.

### Token Priming

After initialization, the server calls `primeToken()` which validates the token with GitHub's Copilot API at `https://api.github.com/copilot_internal/v2/token`. This returns a short-lived session token used for all subsequent API calls.

### `copilot/token` Trap

Setting `copilotCapabilities.token: true` in `initializationOptions` tells the server the client supports the `copilot/token` protocol. The server then sends `copilot/token` requests (with both `id` and `method`) to the client asking for tokens. If the client responds with `null` (the default auto-response), authentication fails with `"Your GitHub token is invalid"`.

**Rule**: Never set `token: true` in `copilotCapabilities` unless you implement the `copilot/token` request handler.

## MCP Server Support

### Nightly/Dev Build Gate

MCP functionality is gated behind a "nightly or dev build" check (`isNightlyOrDevBuild` / `PBn` in main.js):

```js
if (e.name === "copilot-intellij") {
  let r = e.version.endsWith("nightly");  // "1.420.0.nightly" → true
  let n = e.version === "42.0.0.0";       // magic version
  return r || n;
}
```

For other editors:
- `copilot-xcode`: version `"0.0.0"` or `"0.X.Y"` where Y != 0
- `copilot-eclipse`: version ends with `"nightly"` or `"qualifier"`

**If this check fails**, MCP is disabled entirely unless `copilotCapabilities.mcpAllowlist: true` is set. But the allowlist path has its own problems (see below).

**Solution**: Set `editorPluginInfo.version` to end with `"nightly"` (e.g., `"1.420.0.nightly"`). This enables MCP without requiring the allowlist.

### MCP Allowlist (avoid)

Setting `copilotCapabilities.mcpAllowlist: true` enables MCP but activates the registry allowlist feature:

1. Server fetches `https://api.github.com/copilot/mcp_registry` to get allowed servers
2. Only servers on the allowlist can start
3. If the registry is empty or unreachable → `accessMode: "fallback"` → servers are blocked

This caused Playwright to show as `status: "stopped"` with 0 tools. The nightly version approach bypasses this entirely.

### MCP Config Flow

```
workspace/didChangeConfiguration
  → settings.github.copilot.mcp (JSON string, not object)
  → JSON.parse() → updateMCPServers(parsed)
  → toManagedServerDefinition(name, config)
    → "command" in config ? "stdio" : config.type ?? "streamable"
```

### MCP Notifications

| Notification | Structure | When |
|---|---|---|
| `copilot/mcpTools` | `params.servers[]` — array of `{name, prefix, status, tools[]}` | Server connects/disconnects, tools change |
| `copilot/mcpRuntimeLogs` | `params.{message, server, level, time}` | Server lifecycle events |

### MCP Request Methods

| Method | Params | Notes |
|---|---|---|
| `mcp/getTools` | `{}` | Returns `result[]` — array of server objects with nested tools |
| `mcp/serverAction` | `{serverName, action}` | Actions: `start`, `stop`, `restart`, `logout`, `clearOAuth` |
| `mcp/registry/listServers` | `{baseUrl}` | Registry lookup — requires `baseUrl`, not useful for local servers |

### `mcp/getTools` Response Structure

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
          "_status": "enabled",
          "_nameForModel": "browser_navigate",
          "annotations": {
            "title": "Navigate to a URL",
            "readOnlyHint": false,
            "destructiveHint": false,
            "openWorldHint": true
          }
        }
      ]
    }
  ]
}
```

### MCP Policy Enforcement Layers

MCP is controlled at three independent layers. Each can independently block MCP:

| Layer | Where | Bypassable? |
|---|---|---|
| 1. `isNightlyOrDevBuild` check | Client-side, in language server JS | Yes — set version to `"1.420.0.nightly"` |
| 2. GitHub API token flags | Server-side, from `copilot_internal/v2/token` response | No — `getTokenValue("mcp") === "0"` forces `isMcpEnabled = false` |
| 3. `policy/didChange` notification | Server-side, from GitHub org admin settings | No — `"mcp.contributionPoint.enabled": false` disables MCP |

If Layer 2 or 3 blocks MCP, the language server will not start any MCP servers regardless of client configuration.

### Client-Side MCP Bridge (policy bypass)

When server-side MCP is blocked by org policy (Layer 2 or 3), the CLI can run MCP servers directly on the client side and register their tools as regular client tools. The language server cannot distinguish these from normal registered tools.

#### Architecture

```
Server-side MCP (--mcp, blockable):
  CLI → language server → MCP server
               ↑
         policy blocks here

Client-side MCP (auto-fallback, not blockable):
  CLI → spawns MCP server process (stdio)
  CLI → MCP handshake (initialize → tools/list)
  CLI → registers tools via conversation/registerTools
  CLI → bridges invokeClientTool → tools/call
```

#### MCP Protocol (stdio transport)

MCP uses **newline-delimited JSON-RPC 2.0** over stdio — one JSON object per line, terminated by `\n`. This is different from LSP which uses `Content-Length` headers.

Handshake:
```
Client → Server:  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"...","version":"..."}}}
Server → Client:  {"jsonrpc":"2.0","id":1,"result":{"serverInfo":{"name":"Playwright","version":"0.0.64"},...}}
Client → Server:  {"jsonrpc":"2.0","method":"notifications/initialized"}
Client → Server:  {"jsonrpc":"2.0","id":2,"method":"tools/list"}
Server → Client:  {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"browser_navigate","description":"...","inputSchema":{...}},...]}}
```

Tool call:
```
Client → Server:  {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"browser_navigate","arguments":{"url":"https://example.com"}}}
Server → Client:  {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Page Title: Example Domain\n..."}]}}
```

#### Tool Name Mapping

Client-side MCP tools are prefixed `mcp_<server>_<tool>` to avoid collisions:

| MCP tool | Registered as |
|---|---|
| `browser_navigate` | `mcp_playwright_browser_navigate` |
| `browser_click` | `mcp_playwright_browser_click` |

The CLI maintains a map from prefixed name → `(server_name, original_tool_name)` and strips the prefix when forwarding `tools/call`.

#### Response Format Bridge

MCP `tools/call` returns:
```json
{"content": [{"type": "text", "text": "result"}]}
```

The CLI converts this to the registered tool tuple format the Copilot server expects:
```json
[{"content": [{"value": "result"}], "status": "success"}, null]
```

This conversion happens in `_execute_client_tool()` before the result reaches `_handle_server_request()`, so the double-wrapping in `_wrap_registered_tool_result()` is skipped for client-MCP tools.

#### Key Difference from Server-Side MCP

| Aspect | Server-side (org allows) | Client-side (auto-fallback) |
|---|---|---|
| Protocol framing | `Content-Length` headers (LSP-style) | Newline-delimited JSON |
| Process management | Language server spawns/manages | CLI spawns/manages |
| Tool visibility | MCP tools (separate namespace) | Client tools (same as `read_file`, etc.) |
| Policy enforcement | Subject to token flags + org policy | None — invisible to policy system |
| Transport support | stdio + HTTP/streamable | stdio only |

## Proxy Configuration

### Config Path

```
workspace/didChangeConfiguration
  → settings.http.proxy           (URL string)
  → settings.http.proxyStrictSSL  (boolean, default true)
  → settings.http.proxyAuthorization (string, e.g. "Basic base64...")
```

### Proxy Resolution

The server resolves proxy settings in this order:

1. `settings.http.proxy` from `didChangeConfiguration`
2. `HTTPS_PROXY` / `https_proxy` env var
3. `HTTP_PROXY` / `http_proxy` env var

If `proxy` is `undefined` in the settings, falls back to env vars via `getProxyFromEnvironment()`.

### SSL Verification

```
settings.http.proxyStrictSSL: false  →  disable cert verification
NODE_TLS_REJECT_UNAUTHORIZED=0       →  same effect (env var fallback)
```

### Kerberos Support

The server has built-in Kerberos proxy auth support:

```
GH_COPILOT_KERBEROS_SERVICE_PRINCIPAL=HTTP/proxy.host
GITHUB_COPILOT_KERBEROS_SERVICE_PRINCIPAL=HTTP/proxy.host
AGENT_KERBEROS_SERVICE_PRINCIPAL=HTTP/proxy.host
```

### No NTLM Support

The server's proxy code uses `Proxy-Authorization: Basic ...` only. NTLM challenge-response is not implemented. Use a local bridge (CNTLM/Px) for NTLM proxies.

## Copilot Capabilities

`initializationOptions.copilotCapabilities` controls feature flags. Schema is `T.Partial(...)` — all fields are optional booleans.

| Capability | Default | Effect |
|---|---|---|
| `mcpAllowlist` | `false` | Enables MCP with registry allowlist checking (avoid — use nightly version instead) |
| `mcpServerManagement` | `false` | Enables `mcp/serverAction` start/stop/restart |
| `mcpElicitation` | `false` | Enables MCP elicitation prompts |
| `mcpSampling` | `false` | Enables MCP sampling |
| `subAgent` | `false` | Enables sub-agent functionality |
| `token` | `false` | **Dangerous** — makes server request tokens from client via `copilot/token` |
| `fetch` | `false` | Enables fetch capability |
| `watchedFiles` | `false` | Enables file watching |
| `stateDatabase` | `false` | Enables SQLite state persistence |
| `contentProvider` | `[]` | Array of URI schemes the client can provide content for |
| `manageTodoListTool` | `false` | Enables todo list management tool |
| `cveRemediatorAgent` | `false` | Enables CVE remediation agent |
| `debuggerAgent` | `false` | Enables debugger agent |

### Policy Overrides

The server can disable capabilities via policy (from token flags):

```
policy/didChange → {
  "mcp.contributionPoint.enabled": true/false,
  "subagent.enabled": true/false,
  "agentMode.autoApproval.enabled": true/false
}
```

If `editor_preview_features` token value is `"0"`, `mcpAllowlist` and `subAgent` are forced to `false`.

## Initialization Sequence

The correct order for client initialization:

```
1.  start process (spawn copilot-language-server --stdio)
2.  initialize (LSP handshake — includes initializationOptions with githubAppId, editorPluginInfo.version)
3.  initialized notification
4.  setEditorInfo (legacy auth — server reads apps.json here)
5.  configure_proxy (workspace/didChangeConfiguration with settings.http)
6.  checkStatus (verify auth)
7.  configure_mcp (workspace/didChangeConfiguration with settings.github.copilot.mcp)
8.  wait 4s for MCP servers to start
9.  start client-side MCP servers (spawn processes, initialize, tools/list)
10. register_client_tools (conversation/registerTools — non-built-in + client-MCP tools)
11. open documents (textDocument/didOpen)
```

**Critical**: `setEditorInfo` must come before `checkStatus`. MCP config should be sent after auth is confirmed. Client tools must not re-register built-in tools (breaks response format). Client-side MCP servers (step 9) must start before tool registration (step 10) so their tools are included.

## State Persistence

The server uses SQLite for state persistence at `~/.config/github-copilot/copilot-intellij.db`:

```sql
CREATE TABLE state (key TEXT, value TEXT, updated_at INTEGER);
-- Known keys:
--   mcp-first-boot-completed: "true"
--   mcp-servers-cache: JSON blob of cached server metadata
```

## Server-to-Client Requests

Requests with both `id` and `method` are server→client requests that require a response:

| Method | Expected Response | Purpose |
|---|---|---|
| `conversation/invokeClientToolConfirmation` | `[{"result": "accept"}, null]` | Tool execution approval |
| `conversation/invokeClientTool` | Tool result (format depends on built-in vs registered) | Execute a client tool |
| `copilot/watchedFiles` | `{"watchedFiles": []}` | File watch registration |
| `copilot/token` | Token object | Token provider (only if capability enabled) |
| `window/showMessageRequest` | `null` | Show message to user |
