# Copilot Desktop — App Experience Spec

Standalone Electron desktop app that replaces the IntelliJ plugin. Connects directly to the Copilot LSP server. Designed to be a daily-driver developer tool that also looks impressive in demos.

---

## 1. Layout

### Primary Structure

```
┌───────┬──────────────────┬─────────────────────┐
│       │                  │                     │
│ File  │   Chat Panel     │    Flow Graph       │
│ Tree  │                  │                     │
│       │  [User msg]      │   [User]            │
│ src/  │  [Agent resp]    │     ↓               │
│ ├ auth│  [User msg]      │   [Lead]            │
│ ├ lib │  [Agent resp]    │    ↓    ↓           │
│ └ ...│                  │ [Code] [Explore]    │
│       │                  │                     │
├───────┴──────────────────┴─────────────────────┤
│  [  Type a message...                  ] [Send] │
└─────────────────────────────────────────────────┘
```

- **File tree sidebar** (far left): Collapsible. Displays the project directory structure like an IDE. Loaded from the project root selected at startup.
- **Chat panel** (center-left): Scrollable conversation view with streaming markdown.
- **Flow graph** (right): React Flow canvas showing the agent orchestration as a DAG.
- **Chat input** (bottom): Spans the width of the active panes.

### View Modes

The user can switch between three view modes at any time:

| Mode | What's visible |
|------|----------------|
| **Chat only** | File tree + Chat panel, graph hidden |
| **Graph only** | File tree + Flow graph, chat hidden |
| **Split** | File tree + Chat + Graph side-by-side |

In split mode, the divider between chat and graph is **draggable** to resize the panes.

The file tree sidebar is independently collapsible regardless of view mode.

### Conversation History

Conversations are accessed via a **Cmd+K command palette** — not in the sidebar. The palette supports search and is grouped by date. The sidebar stays dedicated to the file tree.

---

## 2. Chat ↔ Graph Synchronization

Chat and graph are **two representations of the same conversation**. They are always in sync:

- **Chat → Graph**: Sending a message creates a new turn section in the graph (UserNode → LeadNode → children).
- **Graph → Chat**: Clicking any node in the graph **scrolls the chat to the corresponding output** and highlights it with a colored left border. The highlight fades after a few seconds.
- **Structural consistency**: Every subagent, tool call, and terminal command visible in the graph has a corresponding entry in the chat (display controlled by user preference — see Section 6).

---

## 3. Flow Graph

### Node Types

| Node | Purpose | Visual |
|------|---------|--------|
| **UserNode** | User's message | Gray border, displays message text |
| **LeadNode** | Lead agent for a turn | Yellow (running), green (done), red (error). Shows "Lead Agent · Turn N" for turns > 1 |
| **SubagentNode** | Delegated subagent | Blue (running), green (success), red (error). Shows agent type, description, text preview |
| **ToolNode** | Tool execution (file read, search, grep, etc.) | Purple (running), green (success), red (error). Shows tool name |
| **TerminalNode** | Shell command execution | Same pattern as ToolNode. Displays the command. Click scrolls to command + output in chat |

### Turn-Based Structure

Each user message starts a new vertical section:

```
[User: "Explore auth modules"]        ← Turn 1
         ↓
   [Lead Agent]
    ↓       ↓
[Explore] [Plan]
         |
         ↓ (dashed)
[User: "Now add JWT"]                 ← Turn 2
         ↓
   [Lead Agent · Turn 2]
    ↓       ↓       ↓
[Code]  [Explore] [Terminal: npm test]
```

- Turns are chained vertically via **dashed gray edges** from the previous turn's lead to the next turn's user node.
- Within a turn, edges from lead to children are **solid and animated** while active.
- Dagre handles layout automatically.

### Visual Rendering

The flow graph combines three visual layers for a rich, readable visualization:

#### 1. Turn Group Containers

Each turn (UserNode → LeadNode → children) is wrapped in a **group node** — a subtle container with:

- Rounded border with faint background tint
- Turn label in the top-left corner ("Turn 1", "Turn 2", ...)
- Enough padding to enclose all child nodes with breathing room
- Groups are connected vertically by the timeline spine

```
┌─ Turn 1 ─────────────────────────────┐
│                                       │
│  ┌──────────────────────────┐         │
│  │  You: Explore auth       │         │
│  └──────────────────────────┘         │
│              │                        │
│  ┌──────────────────────────┐         │
│  │  Lead Agent              │         │
│  └──────────────────────────┘         │
│        │            │                 │
│  ┌───────────┐ ┌───────────┐         │
│  │  Explore  │ │   Plan    │         │
│  │  ✓ done   │ │  running  │         │
│  └───────────┘ └───────────┘         │
└───────────────────────────────────────┘
              │ (spine)
┌─ Turn 2 ─────────────────────────────┐
│  ...                                  │
└───────────────────────────────────────┘
```

#### 2. Timeline Spine

A **vertical line** runs through the center of the graph, connecting turn groups chronologically:

- Solid thin line (`1px`, border color) connecting the bottom of one turn group to the top of the next
- Anchors the user's sense of chronological flow
- Turn group containers are centered on the spine
- The spine extends as new turns are added, auto-scrolling the graph downward

#### 3. Animated Particle Edges

Edges between nodes use **animated particles** to show real-time data flow:

| Edge state | Visual |
|------------|--------|
| **Active (in-progress)** | Particles (small dots) flow along the edge from source to target. Edge has a subtle glow. Color matches the target node type (blue for subagent, purple for tool). |
| **Completed (success)** | Particles stop. Edge settles to a solid line in the success color (green). |
| **Completed (error)** | Particles stop. Edge becomes a solid red line. |
| **Turn chain (dashed)** | No particles. Dashed gray line connecting turns along the spine. Static. |

Particle animation uses CSS `stroke-dashoffset` animation on SVG paths for performance. No canvas/WebGL needed.

### Interaction

- **Click node**: Scrolls chat to corresponding output, shows highlight.
- **Pan and zoom**: Standard React Flow controls (scroll to zoom, drag to pan).
- **Fit view**: Button to auto-fit the entire graph in the viewport.
- **Minimap**: Optional minimap overlay for orientation in large graphs.

---

## 4. Progress & Activity

Both chat and graph provide real-time feedback during agent execution:

### In Chat
- **Streaming text**: Lead agent response streams token-by-token.
- **Status labels**: Step-by-step updates appear as the agent works (e.g., "Spawning explorer...", "Reading files...", "Writing code...").

### In Graph
- **Animated edges**: Pulsing edges connect the lead to active children.
- **Glowing nodes**: Active nodes have an animated pulse indicator.
- **Status transitions**: Nodes visually transition from running → success/error as work completes.

---

## 5. Error Handling

### Display
- **Inline and non-blocking**: Errors appear as a red banner in the chat and a red-bordered node in the graph. Neither blocks the UI.
- **Retry button**: Appears inline next to the error for manual retry.

### Auto-Retry
- Failed agents and subagents are **automatically retried** by default.
- Retry policy (max attempts, backoff strategy, escalation to user) to be defined separately.

### Cancellation
- A **stop button** appears in the chat input area while the agent is running.
- Cancelling stops the current turn. Completed turns are preserved.

---

## 6. Tool Call Display in Chat

The user can choose how tool calls appear in the chat via a **chat display preference** in settings:

| Mode | Behavior |
|------|----------|
| **Collapsible blocks** | Each tool call is a compact labeled block (e.g., `read_file: src/auth.ts`) that expands on click to show input and output |
| **Minimal inline labels** | Small gray labels like `⚙ read_file(src/auth.ts)` between message blocks. Detail is in the graph. |
| **Hidden unless expanded** | Tool calls hidden by default. A "Show N tool calls" toggle reveals them. Focus stays on the agent's written response. |

Default: **Collapsible blocks**.

---

## 7. File Changes

When the agent modifies files, changes are collected in a **dedicated "Changes" panel** (similar to a Git diff view):

- Lists all modified files with a color-coded diff (additions in green, deletions in red).
- User reviews changes and can **accept or reject** per-file or in bulk.
- The file tree sidebar shows a colored indicator on modified files.

The Changes panel is accessible via a tab or button in the toolbar, not always visible.

---

## 8. Tools Management

### Tool Sources

The app provides tools from three sources:

| Source | Examples | Discovery |
|--------|----------|-----------|
| **Built-in** | File read/write, grep, terminal, search | Always available |
| **MCP servers (stdio)** | Custom tool servers started as child processes | Configured in `.copilot/tools.json` |
| **MCP servers (SSE)** | IntelliJ PSI bridge, remote tool servers | Configured in `.copilot/tools.json` |

### Tool Discovery & Configuration

Tools are managed through **both a UI and config files**, bidirectionally synced:

- **Settings UI**: An "Add Server" wizard to configure new MCP servers (type, command/URL, env vars). Writes to `.copilot/tools.json`.
- **`.copilot/tools.json`**: Can be edited directly. Changes are picked up in real time.

### Tool Popover

A **tool icon** next to the chat input opens a popover listing all available tools grouped by source (built-in, each MCP server). Shows:

- Tool name and description
- Status: available, disabled, error
- Permission level
- Usage count for the current session

### MCP Server Lifecycle

Configured MCP servers are **auto-started on app launch** (stdio) or auto-connected (SSE). Status indicators in the tool popover show connected/disconnected/error state. Users can disconnect/reconnect from the popover or settings.

### Tool Permissions

Each tool has a configurable permission level:

| Level | Behavior |
|-------|----------|
| **Auto** | Runs freely when the agent calls it. No user interaction. |
| **Confirm** | Asks for one-click approval the first time per session. Auto-approved after that. |
| **Always ask** | Requires approval every time the agent calls it. |

Defaults: read-only tools (file read, search, grep) are **auto**. Write tools (file write, terminal, git) are **confirm**. Users can change any tool's level in settings.

### Slash Commands

The chat input supports **`/` slash commands** for explicit actions:

| Command | Action |
|---------|--------|
| `/new` | New conversation |
| `/clear` | Clear current conversation |
| `/settings` | Open settings |
| `/tools` | Open tool popover |
| `/commit` | Commit staged changes |
| `/push` | Push to remote |

Typing `/` shows an autocomplete dropdown. Tool and agent selection happens through natural language — no `@` or `#` prefixes.

---

## 9. Networking & Authentication

### Proxy

The app uses **system proxy auto-detection**. On startup, it reads the OS proxy configuration (HTTP_PROXY, HTTPS_PROXY, system network settings) and applies it to all outbound connections (LSP server, MCP servers). The user can override the detected proxy in settings if needed.

### SSL/TLS Certificates

The app trusts the **system certificate store** by default. For corporate environments using MITM proxies or internal CAs, the user can specify an **additional CA certificate file** path in settings or `.copilot/config.json`:

```json
{
  "network": {
    "caCertPath": "/path/to/corporate-ca.pem"
  }
}
```

### GitHub Copilot Authentication

Authentication follows a **discover-then-prompt** flow:

1. **Auto-discover**: On startup, the app checks for existing GitHub Copilot auth tokens from known locations:
   - VS Code: `~/.config/github-copilot/apps.json`
   - JetBrains: `~/.config/github-copilot/hosts.json`
   - Environment variables

2. **If found**: Uses the existing token. Shows the authenticated user in the status bar.

3. **If not found**: Prompts the user with a **GitHub device auth flow** — the same flow used by the IntelliJ Copilot Chat plugin:
   - App displays a device code and a "Copy & Open GitHub" button
   - User authorizes in browser
   - App polls for completion and stores the token

4. **Sign out / switch account**: Available in settings. Clears stored tokens and re-prompts.

---

## 10. Settings & Configuration

### Dual System

Settings are managed through **both a UI and config files**, bidirectionally synced:

- **Settings UI**: Accessible via gear icon or Cmd+Shift+P. Provides a visual editor for all preferences.
- **`.copilot/` config files**: Live in the project root. Editable as code. Changes are picked up by the app in real time.

Editing in either location updates the other.

### Settings Sections

| Section | Contents |
|---------|----------|
| **Connection** | LSP binary path, proxy URL, CA cert path, authentication |
| **Agents** | Custom agent definitions (.md files), tool allowlists/blocklists |
| **Tools** | MCP server configuration, tool permissions, add/remove servers |
| **Theme** | Full token editor (see Section 11) |
| **Chat** | Tool call display mode, font size, streaming speed |
| **Keybindings** | IDE-standard defaults, not heavily customizable for now |

---

## 11. Theming

### Default Theme

The app follows the **OS light/dark mode setting** by default. The user can override this in settings to force light or dark regardless of system preference.

Both light and dark variants ship built-in, styled to match the Citi design system aesthetic.

### Visual Style

Inspired by the **Citi Design System**: clean, corporate-modern aesthetic with generous spacing, systematic typography, and clear visual hierarchy.

- Clean borders and subtle shadows
- Generous whitespace
- Professional, polished look suitable for both daily use and executive demos

### Typography

- **UI text**: [DM Sans](https://fonts.google.com/specimen/DM+Sans) — the primary typeface from the Citi design system. Clean, modern sans-serif with good readability at all sizes. Used for all labels, headings, body text, and navigation.
- **Code**: [JetBrains Mono](https://www.jetbrains.com/lp/mono/) — monospace font for code blocks, terminal output, diffs, and tool call details. Supports ligatures.
- **Fallback stack**: `-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif` for UI; `'Fira Code', 'Cascadia Code', 'Consolas', monospace` for code.

### Token Editor

Users have **full control over the color palette** via a token editor in settings:

| Token | Purpose |
|-------|---------|
| `primary` | Main brand/accent color (buttons, active states, links) |
| `secondary` | Supporting accent (secondary actions, badges) |
| `accent` | Highlight color (selected nodes, active indicators) |
| `background` | App background |
| `surface` | Cards, panels, elevated surfaces |
| `text` | Primary text color |
| `text-secondary` | Muted/secondary text |
| `border` | Borders and dividers |
| `success` | Success states (green by default) |
| `warning` | Warning states (yellow by default) |
| `error` | Error states (red by default) |

Changes preview in real time. Tokens are persisted in `.copilot/theme.json`.

---

## 12. Conversation Persistence

Conversations are stored **per-project** in the `.copilot/conversations/` directory inside the project root.

```
my-project/
└── .copilot/
    ├── config.json          # Project settings
    ├── theme.json           # Theme token overrides
    └── conversations/
        ├── 2026-02-21_explore-auth.json
        ├── 2026-02-21_add-jwt.json
        └── ...
```

- Each conversation is a single JSON file containing the full message history, agent events, and metadata.
- File names include the date and a short summary derived from the first user message.
- Conversations travel with the repo and can be shared with teammates via version control.
- The Cmd+K command palette reads from this directory to list and search past conversations.

---

## 13. Multi-Project Support

- **Multiple windows**: Each project opens in its own Electron window.
- **Multiple tabs per window**: Each window can have multiple conversation tabs.
- Window title shows the project name. Tab title shows the conversation summary or timestamp.
- Opening a new project from File menu spawns a new window.

---

## 14. First-Run Experience

1. App launches with a **project picker** dialog.
2. User selects a project folder.
3. App discovers the LSP binary (checks standard paths for JetBrains, VS Code installations).
4. If auto-discovery fails, prompts for the binary path.
5. Connects to the LSP server and drops into the main view with an empty conversation.

No wizard or multi-step onboarding — just pick a folder and go.

---

## 15. Git Integration

The app has **full git awareness**:

### Status Bar
- Displays the **current branch name** and **uncommitted changes count**.
- Clicking the branch name opens a branch switcher.

### File Tree
- Modified files show a **colored indicator** (dot or highlight):
  - Yellow: modified / staged
  - Green: new / untracked
  - Red: deleted
- Matches the standard IDE convention.

### Git Operations

Available via slash commands and the command palette:

| Command | Action |
|---------|--------|
| `/commit` | Stage and commit with a message |
| `/push` | Push current branch to remote |
| `/pull` | Pull latest from remote |
| `/branch` | Create or switch branches |

The agent can also perform git operations through its terminal tool — these appear as TerminalNodes in the graph.

### Changes Panel Integration

The diff/changes panel (Section 7) integrates with git — it can show both agent-made changes and general working tree changes.

---

## 16. File Editor

The app includes a **built-in code editor**:

- Click a file in the file tree to **open it in a tab** with syntax highlighting.
- Supports basic editing (type, undo/redo, find/replace).
- When the agent modifies a file, the change is reflected in the editor tab in real time.
- The diff panel shows agent changes as a split diff view within the editor.
- Multiple files can be open in tabs simultaneously.
- Not intended to replace a full IDE — covers quick edits, code review, and viewing agent output.

---

## 17. Tool Logging

### Conversation Log (Primary)

Every tool call is logged as part of the **conversation JSON** in `.copilot/conversations/`. Each entry includes:

- Tool name
- Input parameters
- Output / result
- Duration
- Status (success / error)

This is the source of truth. When reviewing a past conversation, tool calls are fully reconstructable.

### Debug Log (Opt-in)

A verbose debug log is available behind a **"Debug logging"** toggle in settings (or `--verbose` CLI flag). When enabled:

- Writes to `.copilot/logs/` with date-rotated files.
- Includes raw LSP messages, MCP protocol frames, and internal state transitions.
- Intended for troubleshooting, not everyday use.

---

## 18. Notifications

- **No popup OS notifications**. The app does not interrupt the user outside its window.
- When the app is in the background and something needs attention (task complete, error, approval needed), the **dock/taskbar icon shows a badge**.
- Badge clears when the user returns to the app window.

---

## 19. Collaboration

Collaboration is **async and git-based**:

- Conversations, agent configs, tool configs, and theme tokens all live in `.copilot/` inside the project root.
- This directory can be committed to the repo and shared with teammates via version control.
- A teammate who clones the repo and opens it in the app sees the same conversation history, agent definitions, and tool configuration.
- No real-time co-viewing or live collaboration — the shared state is the repo.

---

## 20. Keyboard Shortcuts

IDE-standard defaults:

| Shortcut | Action |
|----------|--------|
| `Cmd+K` | Open command palette (conversation history, commands) |
| `Cmd+Enter` | Send message |
| `Cmd+N` | New conversation tab |
| `Cmd+W` | Close current tab |
| `Cmd+/` | Toggle file tree sidebar |
| `Cmd+\` | Toggle between view modes (chat/graph/split) |
| `Cmd+Shift+P` | Open settings |
| `Escape` | Close panels, deselect nodes, cancel search |

---

## 20. Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  Copilot Desktop                                                │
│                                                                 │
│  Standalone LSP-connected Electron app                          │
│  Chat + Flow Graph dual-pane (synchronized, resizable)          │
│  IDE file tree sidebar + Cmd+K conversation history             │
│  Built-in code editor with syntax highlighting                  │
│  Turn-based graph: User → Lead → Subagents/Tools/Terminal       │
│  Streaming progress in both chat and graph                      │
│  Auto-retry on errors, inline + non-blocking                    │
│  Diff panel for file changes                                    │
│  Full git awareness (branch, status, commit/push/pull)          │
│  Tool management: built-in + MCP, configurable permissions      │
│  GitHub Copilot auth: auto-discover + device flow fallback      │
│  System proxy auto-detect, custom CA cert support               │
│  / slash commands, tool popover from chat input                 │
│  DM Sans + JetBrains Mono, system light/dark default            │
│  Full theme token editor, bidirectional settings sync           │
│  Per-conversation tool logging, opt-in debug log                │
│  Per-project persistence in .copilot/, shareable via git        │
│  Multi-window, multi-tab                                        │
│  Badge notifications (no popups)                                │
│  Project picker on first run                                    │
│                                                                 │
│  Target: Developer daily driver + demo-impressive               │
└─────────────────────────────────────────────────────────────────┘
```
