# Copilot CLI
### Bringing GitHub Copilot Beyond the IDE

---

## The Problem

Most AI coding assistants are locked inside IDEs.

- **No CLI access** — Can't use Copilot in headless environments, CI/CD, or terminals
- **No agent mode** — IDE Copilot can autocomplete, but can't autonomously edit files or run commands
- **Corporate restrictions** — Proxy servers, SSL inspection, and org policies block standard setups
- **Limited extensibility** — No way to plug in custom tools (browsers, databases, APIs)

---

## The Solution: Copilot CLI

A lightweight Python CLI that gives you full Copilot capabilities from the terminal.

```mermaid
graph LR
    A[👤 Developer] -->|Terminal| B[Copilot CLI]
    B -->|LSP Protocol| C[Copilot Language Server]
    C -->|HTTPS| D[GitHub Copilot API]
    B -->|stdio| E[MCP Servers]
    E --> F[🌐 Browser]
    E --> G[🗄️ Database]
    E --> H[📁 Filesystem]
    E --> I[🔧 Custom Tools]
```

---

## Key Capabilities

| Capability | Description |
|:-----------|:------------|
| **Chat** | Multi-turn conversation with Copilot from the terminal |
| **Agent Mode** | AI autonomously reads, writes, and executes code |
| **Code Completion** | Inline completions for any file |
| **MCP Integration** | Plug in external tools (browser, DB, APIs) |
| **Proxy Support** | Works behind corporate proxies with auth |
| **Zero Dependencies** | Pure Python — nothing to install |

---

## How It Works

```mermaid
sequenceDiagram
    participant U as Developer
    participant CLI as Copilot CLI
    participant LS as Language Server
    participant API as GitHub API

    U->>CLI: copilot agent "Fix the login bug"
    CLI->>LS: Initialize (auth token)
    LS->>API: Validate token
    API-->>LS: Session established
    CLI->>LS: Start conversation (agent mode)

    loop Agent Loop
        LS-->>CLI: Read file X?
        CLI->>CLI: Execute read_file
        CLI-->>LS: File contents
        LS-->>CLI: Edit file X
        CLI->>CLI: Execute insert_edit
        CLI-->>LS: Edit applied
        LS-->>CLI: Run tests?
        CLI->>CLI: Execute run_in_terminal
        CLI-->>LS: Test results
    end

    LS-->>CLI: Done — here's what I changed
    CLI-->>U: Summary of changes
```

---

## Three Modes of Operation

```mermaid
graph TD
    subgraph Complete["1. Complete"]
        C1[Request completions at cursor position]
        C2[Get multiple suggestions]
    end

    subgraph Chat["2. Chat"]
        CH1[Ask questions about code]
        CH2[Multi-turn conversation]
        CH3[No file modifications]
    end

    subgraph Agent["3. Agent"]
        A1[Give a task in plain English]
        A2[AI reads and searches code]
        A3[AI edits files autonomously]
        A4[AI runs commands and tests]
        A5[Reports results back to you]
    end

    style Agent fill:#e8f5e9,stroke:#4caf50
    style Chat fill:#e3f2fd,stroke:#2196f3
    style Complete fill:#fff3e0,stroke:#ff9800
```

---

## 22 Built-in Tools

The agent has access to a rich set of tools out of the box:

```mermaid
mindmap
  root((Copilot CLI<br/>22 Tools))
    File Operations
      read_file
      create_file
      create_directory
      insert_edit_into_file
      replace_string_in_file
      multi_replace_string
      apply_patch
    Search
      grep_search
      file_search
      find_test_files
      search_workspace_symbols
    Execution
      run_in_terminal
      run_tests
    Navigation
      list_dir
      list_code_usages
      get_errors
    Intelligence
      memory
      get_changed_files
      github_repo
      get_project_setup_info
      get_doc_info
      fetch_web_page
```

---

## MCP: Extending with External Tools

**Model Context Protocol (MCP)** lets you plug in any external capability.

```mermaid
graph TB
    CLI[Copilot CLI] --> MCP1[🎭 Playwright<br/>Browser Automation]
    CLI --> MCP2[🐘 PostgreSQL<br/>Database Queries]
    CLI --> MCP3[💬 Slack<br/>Messaging]
    CLI --> MCP4[📊 Custom API<br/>Any Tool You Build]

    MCP1 --> |"Navigate, click,<br/>screenshot"| Web[Web Apps]
    MCP2 --> |"SELECT, INSERT,<br/>schema inspection"| DB[(Database)]
    MCP3 --> |"Send messages,<br/>read channels"| Slack[Slack Workspace]
    MCP4 --> |"Your custom<br/>logic"| Sys[Internal Systems]

    style CLI fill:#1a237e,color:#fff
    style MCP1 fill:#4a148c,color:#fff
    style MCP2 fill:#004d40,color:#fff
    style MCP3 fill:#b71c1c,color:#fff
    style MCP4 fill:#e65100,color:#fff
```

**Example:** "Navigate to our staging app, find the broken button, and fix the CSS."

The agent uses Playwright to browse, identifies the issue, and edits the code — all in one command.

---

## Smart MCP Routing

The CLI automatically handles corporate policy restrictions:

```mermaid
flowchart TD
    A[MCP Config Provided] --> B{Org Policy<br/>Allows MCP?}
    B -->|Yes| C[Server-Side MCP<br/>Language server manages it]
    B -->|No| D[Client-Side MCP<br/>CLI manages it directly]
    C --> E[Tools Available to Agent]
    D --> E
    E --> F[Agent executes tasks<br/>with full tool access]

    style B fill:#fff9c4,stroke:#f9a825
    style C fill:#c8e6c9,stroke:#43a047
    style D fill:#bbdefb,stroke:#1976d2
```

No manual configuration needed — the CLI detects the policy and routes automatically.

---

## Corporate Proxy Support

Works in restricted corporate environments out of the box:

```mermaid
graph LR
    CLI[Copilot CLI] -->|Authenticated| Proxy[Corporate Proxy]
    Proxy --> Copilot[GitHub Copilot API]

    CLI -.->|Config| Config["copilot_config.toml<br/>or --proxy flag"]

    style Proxy fill:#ffcdd2,stroke:#e53935
```

- HTTP/HTTPS proxy with Basic auth
- SSL inspection / custom CA certificates
- Kerberos authentication support
- TOML config file for persistent settings

---

## Usage Examples

### Interactive Agent Session
```bash
$ python3 copilot_client.py agent

you> Find all TODO comments in the codebase and create GitHub issues for them

copilot> I found 12 TODO comments across 8 files. I've created the following issues:
         - #42: Implement caching layer (src/api/handler.py:23)
         - #43: Add input validation (src/models/user.py:87)
         ...
```

### One-Shot Command
```bash
$ python3 copilot_client.py agent "Add unit tests for the User model"
```

### With MCP Tools
```bash
$ python3 copilot_client.py --mcp mcp.json agent \
    "Check our staging site for broken links and fix any 404s"
```

### Behind a Proxy
```bash
$ python3 copilot_client.py --proxy http://user:pass@proxy:8080 agent
```

---

## Architecture Overview

```mermaid
graph TB
    subgraph CLI["Copilot CLI (Python)"]
        Main[Main Client]
        Tools[22 Built-in Tools]
        MCPBridge[MCP Bridge]
        Auth[Auth Manager]
    end

    subgraph Server["Language Server (Node.js)"]
        LSP[LSP Engine]
        AI[AI Model Interface]
    end

    subgraph External["External Services"]
        GH[GitHub Copilot API]
        MCP1[MCP Server 1]
        MCP2[MCP Server 2]
        MCPN[MCP Server N]
    end

    Main <-->|JSON-RPC / stdio| LSP
    Main --> Tools
    Main --> MCPBridge
    Main --> Auth
    LSP <--> AI
    AI <-->|HTTPS| GH
    MCPBridge <-->|stdio| MCP1
    MCPBridge <-->|stdio| MCP2
    MCPBridge <-->|stdio| MCPN
    Auth -->|OAuth Token| LSP

    style CLI fill:#e8eaf6,stroke:#3f51b5
    style Server fill:#fce4ec,stroke:#e91e63
    style External fill:#e0f2f1,stroke:#009688
```

---

## What Makes This Different

| Feature | IDE Copilot | Copilot CLI |
|:--------|:------------|:------------|
| Terminal / headless use | No | **Yes** |
| Autonomous agent mode | Limited | **Full** |
| MCP tool integration | Plugin-dependent | **Built-in** |
| Corporate proxy support | Varies | **Comprehensive** |
| Org policy bypass for MCP | No | **Auto-routing** |
| External dependencies | Many | **None (pure Python)** |
| Custom tool development | Complex | **Drop a file in tools/** |

---

## Adding a Custom Tool

Adding a new tool is as simple as creating one file:

```python
# tools/my_tool.py

SCHEMA = {
    "name": "my_custom_tool",
    "description": "Does something useful",
    "inputSchema": {
        "type": "object",
        "properties": {
            "input": {"type": "string", "description": "The input to process"}
        },
        "required": ["input"]
    }
}

def execute(tool_input, ctx):
    result = do_something(tool_input["input"])
    return [{"type": "text", "value": result}]
```

Drop it in `tools/` — no registration, no config changes. It just works.

---

## Demo Flow

```mermaid
graph LR
    D1["1. Start agent"] --> D2["2. Ask a question"]
    D2 --> D3["3. Watch it search code"]
    D3 --> D4["4. Watch it edit files"]
    D4 --> D5["5. Watch it run tests"]
    D5 --> D6["6. Review changes"]

    style D1 fill:#c8e6c9
    style D2 fill:#bbdefb
    style D3 fill:#fff9c4
    style D4 fill:#ffe0b2
    style D5 fill:#f8bbd0
    style D6 fill:#d1c4e9
```

**Suggested demo scenarios:**
1. **"Fix a bug"** — Point at a known issue, watch the agent diagnose and fix it
2. **"Add a feature"** — Ask for a new endpoint, see it create files and write tests
3. **"Code review"** — Ask the agent to review recent changes and suggest improvements
4. **"With MCP"** — Connect Playwright, ask it to test a web page

---

## Summary

- **Copilot CLI** brings GitHub Copilot to the terminal with full agent capabilities
- **22 tools** for file editing, search, execution, and more
- **MCP support** extends the agent with any external tool (browsers, databases, APIs)
- **Works in corporate environments** — proxy auth, SSL inspection, policy auto-routing
- **Zero dependencies** — pure Python, drop-in tools, easy to extend
- **Three modes** — completions, chat, and autonomous agent

---

*Built with Python | Zero External Dependencies | LSP + MCP Protocols*
