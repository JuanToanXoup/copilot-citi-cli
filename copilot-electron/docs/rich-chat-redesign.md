# Rich Chat Redesign — Implementation Plan

## Design Intent

Replace the current dual-pane layout (chat bubbles + ReactFlow graph) with a single **rich chat** that embeds tool calls, diffs, file previews, terminal output, and subagent traces directly into the conversation stream. The flow graph is removed entirely — the chat *is* the visualization.

**Reference mockup**: `mockups/option-rich-chat-diffs.html`

### Why

The current flow graph duplicates information already in the chat panel without adding enough value. Users must mentally map between two representations of the same events. The rich chat solves this by making every event visible inline — a single scroll through the conversation tells the full story, including code changes, tool results, and parallel subagent work.

---

## Design Constraints

1. **Single pane** — no split view, no graph toggle. Chat occupies the full content area (minus sidebar/editor when open).
2. **Everything inline** — tool calls, diffs, file reads, terminal output, subagent traces all appear as collapsible cards within the agent's message block, not as separate messages.
3. **Compact by default** — tool cards show one line (icon + name + description + status badge + duration). Expand on click to reveal details.
4. **Streaming-first** — agent text streams into the bubble in real-time. Tool/subagent cards appear as they're invoked, with running indicators that resolve to success/error.
5. **No max-width on chat** — content fills the available width. Diffs and code blocks benefit from horizontal space.
6. **Resizable input** — drag handle between chat history and input area. Input grows vertically up to 500px.

---

## Design Decisions (from mockup)

### Message Structure

Each turn is a `.message-block` containing:
- **Sender row**: avatar + name
- **Bubble(s)**: markdown-rendered agent text (may be multiple, split by tool groups)
- **Tool groups**: compact cards between bubble segments
- **Parallel group**: tabbed component for concurrent subagents
- **Checkpoint**: revert marker after a group of file changes
- **Metrics row**: tokens, duration, cost, files changed

User messages are right-aligned blue bubbles. Agent messages are left-aligned with a subtle border.

### Tool Cards (`.sub-tool` style)

All tool calls — both lead agent and subagent — use the same compact card:

```
[chevron] [icon 20x20] [name bold] [description dim] ... [badge] [duration]
```

- **Icon colors by category**:
  - Read/search: purple bg, purple text (`R`)
  - Edit/create: green bg, lime text (`E`)
  - Terminal: pink bg, pink text (`$`)
  - Search: amber bg, amber text (`S`)
- **Status badge**: `success` (green), `error` (red), `running` (purple pulse)
- **Expand**: click reveals detail panel below (diff, file preview, or terminal output)
- **Collapse by default**: only key results expanded initially

### Diff View

GitHub-style unified diff with:
- File header: icon + path + stats (`+2 -1`)
- Hunk headers: `@@ -1,6 +1,7 @@`
- Line-by-line: old/new gutter numbers, color-coded content
- Inline word-level highlights: `.diff-inline-add` / `.diff-inline-del`
- Addition lines: green tint background, green text
- Deletion lines: red tint background, red text, strikethrough on inline
- Context lines: dim text

### File Preview

For `read_file` results:
- Monospace with line numbers
- Highlighted lines (`.file-highlight`) in amber for points of interest
- Max height 220px with scroll

### Terminal Output

- Dark background, monospace
- Prompt marker (`$`) in pink
- Success text in green
- Max height 180px with scroll

### Parallel Subagents (Tabbed)

When the agent spawns multiple subagents concurrently, they display as a `.parallel-group` with tabs:

**Tab layout** (2 rows per tab):
- Row 1: type badge (EXPLORE/PLAN/CODE) + status dot + duration
- Row 2: description label

**Tab styling**:
- Equal-width tabs (`flex: 1 1 0`), no scrollbar
- Active tab: blue bottom border + darker background
- Type badges color-coded: explore=blue, plan=green, code=purple

**Panel content** (`.sub-trace`):
- Thought text (italic, left-bordered quote style)
- Tool cards (same compact style as lead agent)
- Result summary (green background)
- Metrics (tokens, cost)

### Checkpoints

Horizontal divider with a pill badge:
- Blue dot + label (e.g. "Checkpoint 2 — rate limiter added")
- Hover: blue border, intended as revert/rollback target
- Placed between groups of file changes

### Markdown in Agent Bubbles

Full CommonMark rendering via `marked`:
- Headings (h1-h4), bold, italic, inline code
- Fenced code blocks with syntax highlighting via `highlight.js`
- Ordered/unordered lists
- Blockquotes, tables, horizontal rules, links
- Code block styling: dark background, bordered, monospace

### Mermaid Diagrams

Agent can produce UML diagrams rendered via `mermaid`:
- Wrapped in `.mermaid-wrap` with a label bar
- Dark theme matching the app palette
- Supports all mermaid diagram types (sequence, flowchart, class, ER, state, etc.)

### Context Window Bar

Toolbar widget showing context usage:
- Segmented bar: system (indigo) | history (blue) | files (purple) | free (dark)
- Percentage label

### Streaming State

While agent is working:
- Three bouncing green dots
- "Agent is working..." label
- Tool cards show `running` badge with pulse animation

---

## What to Remove

| Current Component | Action |
|---|---|
| `src/renderer/flow/` (entire directory) | **Delete** — AgentFlow, all node types, ParticleEdge, layout.ts, FlowControls, NodeDetail, MiniMap |
| `src/renderer/stores/create-flow-store.ts` | **Delete** — no flow graph state needed |
| `src/renderer/stores/flow-store.ts` | **Delete** |
| View mode toggle (chat/split/graph) | **Remove** — always chat mode |
| ReactFlow split pane + divider in App.tsx | **Remove** |
| `@xyflow/react` dependency | **Remove** from package.json |
| `dagre` dependency | **Remove** from package.json |

---

## What to Add/Modify

### New Components

| Component | Purpose |
|---|---|
| `ToolCard.tsx` | Compact expandable card for any tool call (replaces separate tool/terminal messages) |
| `DiffView.tsx` | GitHub-style unified diff renderer |
| `FilePreview.tsx` | Read-only file content with line numbers and highlighting |
| `TerminalOutput.tsx` | Terminal command + output display |
| `ParallelGroup.tsx` | Tabbed container for concurrent subagent traces |
| `SubagentTrace.tsx` | Thought + tool cards + result + metrics for one subagent |
| `Checkpoint.tsx` | Revert marker between change groups |
| `MetricsRow.tsx` | Tokens/duration/cost/files badges |
| `ContextBar.tsx` | Segmented context window usage indicator |
| `MermaidDiagram.tsx` | Wrapper for mermaid rendering |
| `MarkdownBubble.tsx` | Agent bubble with markdown + syntax highlighting |
| `ResizeHandle.tsx` | Draggable divider between chat scroll and input area |
| `StreamingIndicator.tsx` | Bouncing dots + status text |

### Modified Components

| Component | Changes |
|---|---|
| `ChatPanel.tsx` | Rewrite — render message blocks with embedded tool cards, parallel groups, checkpoints. Replace simple bubble rendering with rich markdown. |
| `App.tsx` | Remove flow graph pane, split view logic, flow store refs. Simplify event handler to only update agent store. Pass richer data (tool input/output, subagent traces) to agent store. |
| `ChatInput.tsx` | Add resize handle above input. Make textarea auto-grow with drag-to-resize support. |

### Modified Stores

| Store | Changes |
|---|---|
| `create-agent-store.ts` | Restructure messages to support embedded tool calls within an agent turn. Add `toolCalls[]` array on agent messages instead of separate tool messages. Add `parallelGroups[]` for concurrent subagents. Track checkpoint boundaries. Store tool input/output directly on tool entries. |

### New Dependencies

| Package | Purpose |
|---|---|
| `marked` | Markdown parsing |
| `highlight.js` | Syntax highlighting for code blocks |
| `mermaid` | UML/diagram rendering |

### Remove Dependencies

| Package | Reason |
|---|---|
| `@xyflow/react` | Flow graph removed |
| `dagre` | Graph layout removed |

---

## Data Model Changes

### Current: Flat message list

```typescript
messages: ChatMessage[]  // user, agent, tool, subagent, terminal all separate
```

### New: Turn-based with embedded events

```typescript
interface Turn {
  id: string
  userMessage: string
  agentSegments: AgentSegment[]  // text + tool groups, interleaved
  parallelGroups: ParallelGroup[]
  checkpoints: Checkpoint[]
  metrics: TurnMetrics
  status: 'streaming' | 'done' | 'error'
}

interface AgentSegment {
  type: 'text' | 'toolGroup'
  // if text:
  markdown?: string
  // if toolGroup:
  tools?: ToolEntry[]
}

interface ToolEntry {
  id: string
  name: string
  description: string  // 1-line input summary
  status: 'running' | 'success' | 'error'
  duration?: number
  category: 'read' | 'edit' | 'terminal' | 'search' | 'create'
  detail?: ToolDetail
}

type ToolDetail =
  | { type: 'diff'; filePath: string; hunks: DiffHunk[] }
  | { type: 'file'; filePath: string; lines: FileLine[]; highlights?: number[] }
  | { type: 'terminal'; command: string; output: string }
  | { type: 'search'; results: string[] }

interface ParallelGroup {
  id: string
  agents: SubagentEntry[]
}

interface SubagentEntry {
  id: string
  type: string        // 'explore' | 'plan' | 'code' | etc.
  label: string       // short description
  status: 'running' | 'success' | 'error'
  duration?: number
  thought?: string
  tools: ToolEntry[]
  result?: string
  metrics?: { tokens: number; cost: number }
}

interface Checkpoint {
  id: string
  label: string
  afterToolIndex: number  // position in the segment list
}

interface TurnMetrics {
  tokens: number
  duration: number
  cost: number
  filesChanged: number
}
```

---

## Event Handler Mapping

How backend events map to the new data model:

| Event | Action |
|---|---|
| User submits message | Create new `Turn`, set `userMessage` |
| `lead:started` | Set turn status to `streaming` |
| `lead:delta` | Append to current text segment's `markdown` |
| `lead:toolcall` | End current text segment, start new `toolGroup` segment, add `ToolEntry` with `running` status |
| `lead:toolresult` | Update matching `ToolEntry` with status + detail (parse diff/file/terminal from output) |
| `subagent:spawned` | Create `ParallelGroup` (or add to existing if concurrent). Add `SubagentEntry` with `running` |
| `subagent:delta` | Append to subagent's thought or streaming text |
| `subagent:completed` | Update subagent status, add result text |
| `lead:done` | Set turn status to `done`, compute metrics |
| `lead:error` | Set turn status to `error` |
| `file:changed` | Increment turn's `filesChanged`, potentially create checkpoint |

---

## Execution Order

### Phase 1: Strip the graph (1 task)
1. Remove flow graph pane, split view, and view mode toggle from App.tsx
2. Delete `src/renderer/flow/` directory
3. Delete flow store files
4. Remove `@xyflow/react` and `dagre` from package.json
5. Verify clean compilation

### Phase 2: Add rendering dependencies (1 task)
1. Install `marked`, `highlight.js`, `mermaid`
2. Create utility wrappers: `renderMarkdown(text)`, `highlightCode(code, lang)`, `renderMermaid(el)`

### Phase 3: Build atomic components (4-5 tasks, parallelizable)
1. `ToolCard` — compact header + expandable detail
2. `DiffView` — unified diff with line numbers and inline highlights
3. `FilePreview` — line-numbered file content
4. `TerminalOutput` — styled command + output
5. `MarkdownBubble` — marked + highlight.js rendering
6. `MermaidDiagram` — mermaid wrapper with dark theme
7. `Checkpoint` — divider with revert badge
8. `MetricsRow` — token/duration/cost/files badges
9. `StreamingIndicator` — bouncing dots
10. `ResizeHandle` — drag-to-resize divider

### Phase 4: Build composite components (2 tasks)
1. `SubagentTrace` — thought + tool cards + result + metrics
2. `ParallelGroup` — tabbed container using SubagentTrace panels

### Phase 5: Restructure agent store (1 task)
1. Replace flat `messages[]` with `turns: Turn[]`
2. Rewrite `handleEvent()` to build turn-based structure
3. Preserve `loadConversation()` for saved conversations

### Phase 6: Rewrite ChatPanel (1 task)
1. Render turns with interleaved text segments and tool groups
2. Embed ParallelGroup for concurrent subagents
3. Place checkpoints between change groups
4. Add metrics row at end of each turn

### Phase 7: Update App.tsx (1 task)
1. Remove all flow store references and event dispatching
2. Simplify to only update agent store
3. Pass richer event data (tool input/output) from backend events
4. Add resize handle to input area

### Phase 8: Polish and verify (1 task)
1. Clean compilation (`npx tsc --noEmit`)
2. Send message → agent text streams, tool cards appear inline
3. Expand/collapse tool cards
4. Parallel subagents render in tabbed component
5. Checkpoints appear after file changes
6. Load saved conversation → full reconstruction
7. Markdown renders with syntax highlighting
8. Mermaid diagrams render in dark theme

---

## Style Guide

### Colors (CSS custom properties)

Backgrounds: `--bg-primary` (#0a0a0f), `--bg-secondary` (#111118), `--bg-tertiary` (#12121e)
Borders: `--border` (#1e1e2e), `--border-light` (#2a2a3e)
Text: `--text-primary` (#e2e8f0) → `--text-faint` (#475569)
Accents: blue (#3b82f6), green (#22c55e), red (#ef4444), purple (#a78bfa), pink (#c084fc), amber (#fbbf24)
Each accent has a `*-dim` variant for backgrounds.

### Typography

- Body: system font stack, 14px, line-height 1.65
- Code: 'SF Mono', Monaco, monospace, 12px
- Tool names: 11-12px, semibold
- Badges: 9-10px, semibold, pill shape
- Metrics: 10-11px, monospace values

### Spacing

- Turn gap: 24px
- Tool card gap: 6px
- Bubble padding: 12px 16px
- Tool header padding: 8px 10px

### Animations

- Chevron rotate: 0.2s ease
- Badge pulse: 1.5s infinite (opacity 1 → 0.4 → 1)
- Streaming dots: 1.2s bounce, staggered 0.15s
- Hover transitions: 0.15s

---

## Open Questions

1. **Checkpoint revert** — should clicking a checkpoint actually undo changes, or just scroll/highlight? (Current mockup: hover effect only, no implementation)
2. **Tool card default state** — should diffs auto-expand for edit/create tools? (Current mockup: edit_file expanded, read_file expanded, search collapsed)
3. **Conversation save format** — the new turn-based structure differs from the current flat message list. Need migration path for saved conversations.
4. **Context window bar** — where does the data come from? Backend needs to expose token usage per segment (system, history, files, free).
5. **Cost tracking** — does the backend provide per-turn cost, or should the frontend estimate from token counts?

---

## Appendix: HTML Mockup Reference

The source of truth for visual design is `mockups/option-rich-chat-diffs.html`. Open it in a browser to see the full interactive mockup. Below are the key HTML patterns to replicate as React components.

### Message Block Structure

```html
<!-- User turn -->
<div class="message-block user">
  <div class="sender-row">
    <div class="avatar user">J</div>
    <span class="sender">You</span>
  </div>
  <div class="bubble user">Fix the login bug in auth.ts</div>
</div>

<!-- Agent turn -->
<div class="message-block agent">
  <div class="sender-row">
    <div class="avatar agent">A</div>
    <span class="sender">Agent</span>
  </div>

  <!-- Agent text (markdown-rendered) -->
  <div class="bubble agent md">I'll investigate the login function in `auth.ts`.</div>

  <!-- Inline tool cards -->
  <div class="tool-group">
    <div class="sub-tool expanded" id="tool1" onclick="toggleSubTool('tool1')">
      <div class="sub-tool-header">
        <span class="sub-tool-chevron">&#9654;</span>
        <div class="sub-tool-icon" style="background:var(--purple-dim);color:var(--purple);">R</div>
        <span class="sub-tool-name">read_file</span>
        <span class="sub-tool-desc">src/auth.ts</span>
        <span class="sub-tool-badge success">success</span>
        <span class="sub-tool-dur">0.3s</span>
      </div>
      <div class="sub-tool-detail">
        <!-- file preview, diff, or terminal output here -->
      </div>
    </div>
  </div>

  <!-- More agent text after tool results -->
  <div class="bubble agent md">Found the bug on **line 7**...</div>

  <!-- Checkpoint -->
  <div class="checkpoint">
    <div class="checkpoint-line"></div>
    <div class="checkpoint-badge"><div class="dot"></div>Checkpoint 1 — fixed bcrypt</div>
    <div class="checkpoint-line"></div>
  </div>

  <!-- Metrics -->
  <div class="metrics-row">
    <div class="metric">Tokens <span class="val">1,180</span></div>
    <div class="metric">Duration <span class="val">2.8s</span></div>
    <div class="metric">Cost <span class="val">$0.004</span></div>
  </div>
</div>
```

### Tool Card (compact, all tool types)

```html
<div class="sub-tool" id="toolN" onclick="toggleSubTool('toolN')">
  <div class="sub-tool-header">
    <span class="sub-tool-chevron">&#9654;</span>
    <!-- Icon varies by tool type -->
    <div class="sub-tool-icon" style="background:var(--purple-dim);color:var(--purple);">R</div>
    <span class="sub-tool-name">read_file</span>
    <span class="sub-tool-desc">src/auth.ts</span>
    <span class="sub-tool-badge success">success</span>
    <span class="sub-tool-dur">0.3s</span>
  </div>
  <div class="sub-tool-detail">
    <!-- Content depends on tool type -->
  </div>
</div>
```

**Icon mapping:**
| Category | Background | Color | Letter |
|---|---|---|---|
| read_file | `var(--purple-dim)` | `var(--purple)` | R |
| edit_file, create_file | `#1a2e05` | `#a3e635` | E |
| terminal | `var(--pink-dim)` | `var(--pink)` | $ |
| search_files | `var(--amber-dim)` | `var(--amber)` | S |

### Diff Detail

```html
<div class="diff-container">
  <div class="diff-file-header">
    <span class="diff-file-icon">&#128196;</span>
    <span class="diff-file-path">src/auth.ts</span>
    <div class="diff-stats"><span class="add">+2</span><span class="del">-1</span></div>
  </div>
  <div class="diff-hunk-header">@@ -1,11 +1,12 @@</div>
  <div class="diff-line context">
    <div class="diff-gutter"><span class="old-ln">1</span><span class="new-ln">1</span></div>
    <div class="diff-content">import { db } from './database'</div>
  </div>
  <div class="diff-line deletion">
    <div class="diff-gutter"><span class="old-ln">7</span><span class="new-ln"></span></div>
    <div class="diff-content">  if (<span class="diff-inline-del">user.password === password</span>) {</div>
  </div>
  <div class="diff-line addition">
    <div class="diff-gutter"><span class="old-ln"></span><span class="new-ln">8</span></div>
    <div class="diff-content">  if (<span class="diff-inline-add">await bcrypt.compare(password, user.passwordHash)</span>) {</div>
  </div>
</div>
```

### File Preview Detail

```html
<div class="file-preview">
  <div class="file-line">
    <span class="file-ln">1</span>
    <span class="file-code">import { db } from './database'</span>
  </div>
  <div class="file-line file-highlight">
    <span class="file-ln">7</span>
    <span class="file-code">  if (user.password === password) {</span>
  </div>
</div>
```

### Terminal Output Detail

```html
<div class="term-output">
  <span class="term-prompt">$</span> npx jest --testPathPattern rate-limit
  <span class="term-success">PASS</span>  src/__tests__/rate-limit.test.ts
  Login Rate Limiting
    <span class="term-success">&#10003;</span> allows 5 login attempts (48 ms)
    <span class="term-success">&#10003;</span> blocks the 6th attempt (12 ms)

  Test Suites: <span class="term-success">1 passed</span>, 1 total
  Tests:       <span class="term-success">2 passed</span>, 2 total
</div>
```

### Parallel Subagent Tabs

```html
<div class="parallel-group" id="pg1">
  <div class="parallel-tabs">
    <div class="parallel-tab active" onclick="switchTab('pg1','explore')">
      <div class="tab-row1">
        <span class="tab-type explore">Explore</span>
        <span class="tab-status success"></span>
        <span class="tab-dur">1.0s</span>
      </div>
      <div class="tab-row2">
        <span class="tab-label">Check middleware</span>
      </div>
    </div>
    <div class="parallel-tab" onclick="switchTab('pg1','plan')">
      <div class="tab-row1">
        <span class="tab-type plan">Plan</span>
        <span class="tab-status success"></span>
        <span class="tab-dur">1.3s</span>
      </div>
      <div class="tab-row2">
        <span class="tab-label">Rate limit design</span>
      </div>
    </div>
    <!-- more tabs... -->
  </div>

  <div class="parallel-panel active" data-group="pg1" data-tab="explore">
    <div class="sub-trace">
      <div class="sub-thought">I need to find existing middleware files...</div>
      <!-- sub-tool cards here -->
      <div class="sub-result">&#10003; Found middleware.ts with CORS and auth.</div>
      <div class="sub-metrics">
        <span class="sub-metric">620 tokens</span>
        <span class="sub-metric">$0.002</span>
      </div>
    </div>
  </div>

  <div class="parallel-panel" data-group="pg1" data-tab="plan">
    <!-- plan panel content -->
  </div>
</div>
```

### Mermaid Diagram

```html
<div class="mermaid-wrap">
  <div class="mermaid-label">
    <span class="mermaid-label-icon">&#9672;</span> Login flow (after fix)
  </div>
  <pre class="mermaid">
sequenceDiagram
    participant U as User
    participant S as Server
    U->>S: POST /auth/login
    S->>S: bcrypt.compare(password, hash)
    alt Valid
        S-->>U: 200 + session token
    else Invalid
        S-->>U: 401 Invalid credentials
    end
  </pre>
</div>
```

### Streaming Indicator

```html
<div class="streaming">
  <div class="streaming-dots"><span></span><span></span><span></span></div>
  <span class="streaming-text">Agent is working...</span>
</div>
```

### Resize Handle + Input Area

```html
<div class="resize-handle" id="resizeHandle"><div class="grip"></div></div>
<div class="chat-input-area" id="inputArea" style="height: 90px;">
  <div class="input-wrap">
    <textarea class="input-box" rows="1" placeholder="Send a message..."></textarea>
    <button class="send-btn">&#8593;</button>
  </div>
  <div class="input-hints">
    <span class="input-hint"><kbd>Enter</kbd> send</span>
    <span class="input-hint"><kbd>Shift+Enter</kbd> newline</span>
    <span class="input-hint"><kbd>Esc</kbd> cancel</span>
  </div>
</div>
```

### Complete CSS Variables

```css
:root {
  --bg-primary: #0a0a0f;
  --bg-secondary: #111118;
  --bg-tertiary: #12121e;
  --bg-hover: #16162a;
  --border: #1e1e2e;
  --border-light: #2a2a3e;
  --text-primary: #e2e8f0;
  --text-secondary: #cbd5e1;
  --text-muted: #94a3b8;
  --text-dim: #64748b;
  --text-faint: #475569;
  --blue: #3b82f6;
  --blue-dim: #1e3a5f;
  --green: #22c55e;
  --green-dim: #052e16;
  --green-light: #4ade80;
  --red: #ef4444;
  --red-dim: #450a0a;
  --red-light: #fca5a5;
  --purple: #a78bfa;
  --purple-dim: #2e1065;
  --pink: #c084fc;
  --pink-dim: #3b0764;
  --amber: #fbbf24;
  --amber-dim: #713f12;
}
```
