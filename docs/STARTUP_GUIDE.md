# Copilot CLI — Engineer Startup Guide

> Get from zero to productive in ~10 minutes.

---

## Prerequisites Checklist

Before you begin, confirm you have these:

- [ ] **Python 3.10+** — verify with `python3 --version` (macOS/Linux) or `python --version` (Windows)
- [ ] **GitHub Copilot license** — active individual or org subscription
- [ ] **JetBrains IDE installed** (IntelliJ, PyCharm, WebStorm, etc.) — needed for the language server binary
- [ ] **Signed into GitHub Copilot in your IDE at least once** — this creates the auth token file

---

## Step 1: Clone the Repo

```bash
git clone <repo-url>
cd copilot-citi-cli
```

No `pip install`, no virtual env, no dependencies. It's pure Python.

---

## Step 2: Locate Your Language Server Binary

The CLI needs the `copilot-language-server` binary bundled with your JetBrains IDE.

**macOS (Apple Silicon):**
```bash
ls ~/Library/Application\ Support/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/darwin-arm64/copilot-language-server
```

**macOS (Intel):**
```bash
ls ~/Library/Application\ Support/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/darwin-x64/copilot-language-server
```

**Linux:**
```bash
ls ~/.local/share/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/linux-x64/copilot-language-server
```

**Windows:**
```powershell
dir $env:APPDATA\JetBrains\*\plugins\github-copilot-intellij\copilot-agent\native\win32-x64\copilot-language-server.exe
```

Copy the full path — you'll need it in the next step.

---

## Step 3: Create Your Config File

Create `copilot_config.toml` in the project root:

```toml
# Copilot CLI Configuration
# All paths support ~ expansion and * wildcards

copilot_binary = "~/Library/Application Support/JetBrains/*/plugins/github-copilot-intellij/copilot-agent/native/darwin-arm64/copilot-language-server"
apps_json = "~/.config/github-copilot/apps.json"
default_model = "gpt-4.1"

# Uncomment and set your proxy if behind a corporate network
# [proxy]
# url = "http://your-proxy:8080"
# no_ssl_verify = false

# MCP servers (optional) - auto-routes to server-side if org allows,
# otherwise falls back to client-side
# [mcp.playwright]
# command = "npx"
# args = ["-y", "@playwright/mcp@latest"]
```

---

## Step 4: Verify Auth

Make sure your GitHub Copilot token exists:

**macOS / Linux:**
```bash
cat ~/.config/github-copilot/apps.json
```

**Windows (PowerShell):**
```powershell
Get-Content "$env:APPDATA\github-copilot\apps.json"
```

You should see JSON with an `oauth_token` field. If this file doesn't exist, open your JetBrains IDE, sign into GitHub Copilot, then check again.

---

## Step 5: Smoke Test

Run the simplest command to verify everything works:

**macOS / Linux:**
```bash
python3 copilot_client.py models
```

**Windows:**
```powershell
python copilot_client.py models
```

If you see a list of models (e.g., `gpt-4.1`, `claude-sonnet-4`), you're good to go.

---

## Your First 5 Commands

Try these in order to build familiarity:

### 1. Ask a question (Chat mode)
```bash
python3 copilot_client.py chat "What is a Python decorator?"
```
Read-only. No files are touched.

### 2. Get a code completion
```bash
python3 copilot_client.py complete copilot_client.py -l 10 -c 0
```
Returns what Copilot would autocomplete at line 10, column 0.

### 3. Start an interactive chat
```bash
python3 copilot_client.py chat
```
Multi-turn conversation. Type your questions, press Enter. Type `exit` to quit.

### 4. Run the agent on your codebase
```bash
python3 copilot_client.py -w ~/your-project agent "Explain the project structure"
```
The agent reads files autonomously and gives you a summary. Safe — this only reads.

### 5. Let the agent make changes
```bash
python3 copilot_client.py -w ~/your-project agent "Add docstrings to all public functions in src/main.py"
```
The agent edits files. You'll see each tool call as it happens.

---

## Understanding the Three Modes

| Mode | What it does | Modifies files? | Best for |
|:-----|:-------------|:-----------------|:---------|
| `complete` | Returns inline code suggestions at a cursor position | No | IDE-like autocomplete from terminal |
| `chat` | Multi-turn conversation about code | No | Asking questions, explaining code, brainstorming |
| `agent` | Autonomous task execution with tools | **Yes** | Bug fixes, feature work, refactoring, test writing |

---

## Common Workflows

### Bug Fixing
```bash
python3 copilot_client.py -w ~/project agent "The login endpoint returns 500 when email is empty. Find and fix the bug."
```

### Writing Tests
```bash
python3 copilot_client.py -w ~/project agent "Write unit tests for src/services/auth.py"
```

### Code Review
```bash
python3 copilot_client.py -w ~/project agent "Review the recent changes in git and suggest improvements"
```

### One-off Scripts
```bash
python3 copilot_client.py agent "Write a Python script that converts all CSV files in /tmp/data to JSON"
```

---

## Adding MCP Tools (Optional Power-Up)

MCP lets you give the agent access to external tools like browsers, databases, and APIs.

### Quick Setup

1. Create `mcp.json`:
```json
{
  "servers": {
    "playwright": {
      "command": "npx",
      "args": ["-y", "@playwright/mcp@latest"]
    }
  }
}
```

2. Run with MCP:
```bash
python3 copilot_client.py --mcp mcp.json agent "Go to example.com and take a screenshot"
```

See [MCP_GUIDE.md](MCP_GUIDE.md) for the full list of available MCP servers and configuration options.

---

## Adding a Custom Tool (Optional)

Want the agent to do something specific to your team? Create a file in `tools/`:

```python
# tools/my_tool.py

SCHEMA = {
    "name": "my_custom_tool",
    "description": "Describe what this tool does — the AI reads this to decide when to use it",
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "What to look up"}
        },
        "required": ["query"]
    }
}

def execute(tool_input, ctx):
    result = do_your_thing(tool_input["query"])
    return [{"type": "text", "value": str(result)}]
```

No registration needed. Drop the file in `tools/` and it's automatically discovered.

See [COPILOT_TOOLS.md](COPILOT_TOOLS.md) for the full guide.

---

## Troubleshooting

| Problem | Fix |
|:--------|:----|
| `FileNotFoundError` for language server | Double-check your `copilot_binary` path in `copilot_config.toml`. Update after IDE upgrades. |
| `apps.json` not found | Sign into GitHub Copilot in your JetBrains IDE first. |
| `401 Unauthorized` | Your Copilot token may have expired. Re-sign-in to your IDE to refresh it. |
| Connection timeout | If behind a proxy, add the `[proxy]` section to your config. See [PROXY_GUIDE.md](PROXY_GUIDE.md). |
| `SSL: CERTIFICATE_VERIFY_FAILED` | Your corporate proxy likely does SSL inspection. Set `no_ssl_verify = true` in your config. |
| Agent not making edits | Make sure you're using `agent` mode, not `chat`. Chat is read-only. |

---

## Quick Reference Card

```
Usage:  python3 copilot_client.py [flags] <mode> [args]

Modes:
  models                      List available AI models
  complete <file> -l N -c N   Get completions at line/column
  chat [message]              Chat (interactive or one-shot)
  agent [task]                Agent mode (interactive or one-shot)

Flags:
  -w, --workspace <dir>       Set the working directory
  -m, --model <name>          Choose model (e.g., gpt-4.1)
  --mcp <config.json>         Enable MCP tools
  --proxy <url>               Set proxy URL
  --no-ssl-verify             Skip SSL certificate checks

Examples:
  python3 copilot_client.py chat "Explain async/await"
  python3 copilot_client.py -w ~/project agent "Fix the login bug"
  python3 copilot_client.py --mcp mcp.json agent "Test the web app"
  python3 copilot_client.py --proxy http://proxy:8080 models
```

---

## Further Reading

- [PROXY_GUIDE.md](PROXY_GUIDE.md) — Corporate proxy and SSL setup
- [MCP_GUIDE.md](MCP_GUIDE.md) — Server-side MCP integration
- [CLIENT_MCP_GUIDE.md](CLIENT_MCP_GUIDE.md) — Client-side MCP (bypasses org policies)
- [COPILOT_TOOLS.md](COPILOT_TOOLS.md) — Building custom tools
- [GITHUB_AUTH.md](GITHUB_AUTH.md) — How authentication works
- [INTERNALS.md](INTERNALS.md) — Language server internals (for contributors)
