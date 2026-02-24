# CLAUDE.md — Project Memory

## MCP Tool Registration Architecture

**Key finding (2026-02-24):** Client-side MCP tools (e.g. Playwright) must be registered
with the Copilot server via `conversation/registerTools` as regular client tools. This is
the same channel used for built-in tools and does NOT trigger content policy filtering.

The server's content policy only applies to the **server-side MCP channel**
(`workspace/didChangeConfiguration`). When the server rejects MCP tools through that
channel, the client falls back to client-side MCP and registers tools via `registerTools`.

### How it works

1. `ClientMcpManager` spawns MCP server processes locally (stdio/SSE)
2. Tool schemas are registered via `conversation/registerTools` alongside built-in tools
3. The server sends `conversation/invokeClientTool` when the model calls an MCP tool
4. `ConversationManager` / `AgentService` / `LspClientPool` check `clientMcpManager.isMcpTool()`
   and route to `clientMcpManager.callTool()` — execution never touches the server

### What NOT to do

- Do NOT use `workspace/didChangeConfiguration` for MCP config — triggers content policy
- Do NOT skip registering MCP tools with the server — the model won't call unregistered tools
- Do NOT sanitize/rename tool names — the CLI registers them with original names and it works

### Reference

- Python CLI: `cli/src/copilot_cli/client.py` — `register_client_tools()` (line ~667)
- Kotlin: `copilot-chat/.../conversation/LspSession.kt` — `registerTools()`
- MCP manager: `copilot-chat/.../mcp/ClientMcpManager.kt`
